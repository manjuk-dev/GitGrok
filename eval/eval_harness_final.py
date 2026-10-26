"""
GitGrok Retrieval Eval Harness (Python)
----------------------------------------
Runs a fixed set of golden queries against a running GitGrok instance over HTTP,
scores whether the streamed chat answer mentions the expected file/method, and
records latency + a rough cost estimate per query. Negative queries (things the
codebase does not contain) pass only when GitGrok says it found nothing relevant.

Usage (PowerShell):
    pip install requests
    $env:GITGROK_BASE_URL = "http://<oci-ip>:8080"
    python eval_harness_final.py

Exits non-zero if accuracy falls below ACCURACY_THRESHOLD (CI regression gate).
"""

import json
import os
import re
import sys
import time
import urllib.parse
from dataclasses import dataclass, asdict, field
from datetime import datetime, timezone
from typing import Optional

import requests

# ---- CONFIG ---------------------------------------------------------------
BASE_URL = os.environ.get("GITGROK_BASE_URL", "http://localhost:8080")
SEARCH_ENDPOINT = "/chat"
TIMEOUT_SECONDS = 30
DELAY_BETWEEN_QUERIES = 18  # seconds, keeps you under Groq free-tier limits
MAX_RETRIES = 3

EMBEDDING_COST_PER_QUERY = 0.00002
LLM_COST_PER_QUERY = 0.0015

# Lower this until retrieval improves, then raise it as a regression gate
ACCURACY_THRESHOLD = 50.0

REPORT_PATH = "gitgrok-eval-report.json"

# Phrases GitGrok uses when it finds nothing relevant (used for negative queries)
NOT_FOUND_MARKERS = ["file not found in context", "snippet ends prematurely"]
NEGATIVE_EXTRA_MARKERS = ["no relevant", "not present in"]


# ---- DATA TYPES -------------------------------------------------------------
@dataclass
class GoldenQuery:
    query: str
    expected_file: list[str]
    expected_method: Optional[list[str]] = None
    adversarial: bool = False
    should_retrieve: bool = True  # False = negative query, expects "nothing found"
    out_of_scope: bool = False  # by-design miss (e.g. class-level queries)

    def __post_init__(self):
        if isinstance(self.expected_file, str):
            self.expected_file = [self.expected_file]
        if isinstance(self.expected_method, str):
            self.expected_method = [self.expected_method]


@dataclass
class QueryResult:
    query: GoldenQuery
    hit: bool
    rank: int
    latency_ms: float
    reason: str = ""


@dataclass
class EvalReport:
    timestamp: str
    total_queries: int
    hits: int
    accuracy: float
    p50_latency: float
    p95_latency: float
    estimated_cost: float
    failures: list = field(default_factory=list)
    in_scope_accuracy: float = 0.0


# ---- GOLDEN QUERY SET -------------------------------------------------------
def load_golden_queries() -> list[GoldenQuery]:
    return [
        # ============================================================
        # BASIC RETRIEVAL
        # ============================================================
        GoldenQuery(
            query="where is the owner entity defined",
            expected_file=["Owner.java"],
            out_of_scope=True,
        ),
        GoldenQuery(
            query="where is the pet entity defined",
            expected_file=["Pet.java"],
            out_of_scope=True,
        ),
        GoldenQuery(
            query="where is the vet entity defined",
            expected_file=["Vet.java"],
            out_of_scope=True,
        ),
        GoldenQuery(
            query="where is the visit entity defined",
            expected_file=["Visit.java"],
            out_of_scope=True,
        ),
        GoldenQuery(
            query="where are pet types defined",
            expected_file=["PetType.java"],
            out_of_scope=True,
        ),

        # ============================================================
        # METHOD / IMPLEMENTATION RETRIEVAL
        # ============================================================
        GoldenQuery(
            query="how are owners searched by last name",
            expected_file=["JdbcOwnerRepositoryImpl.java"],
            expected_method=["findByLastName"],
        ),
        GoldenQuery(
            query="how is a new pet created",
            expected_file=["PetRestControllerV1.java", "OwnerRestControllerV1.java"],
            expected_method=["addPetToOwner"],
        ),
        GoldenQuery(
            query="how is a pet removed from an owner",
            expected_file=["OwnerRestControllerV1.java"],
            expected_method=["deletePet"],
        ),
        GoldenQuery(
            query="how are vets retrieved from the database",
            expected_file=[
                "VetRepository.java",
                "JpaVetRepositoryImpl.java",
                "JdbcVetRepositoryImpl.java",
            ],
            expected_method=["findAll"],
        ),
        GoldenQuery(
            query="how is a visit associated with a pet",
            expected_file=["OwnerRestControllerV1.java", "VisitRestControllerV1.java"],
            expected_method=["addVisitToOwner", "addVisit"],
        ),

        # ============================================================
        # MULTI-HOP / RELATIONSHIP
        # ============================================================
        GoldenQuery(
            query="how does a request to add a pet flow from the REST controller to the database",
            expected_file=[
                "OwnerRestControllerV1.java",
                "PetRepository.java",
                "JpaPetRepositoryImpl.java",
                "JdbcPetRepositoryImpl.java",
            ],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does the system retrieve an owner and then access their pets",
            expected_file=["Owner.java", "OwnerRestControllerV1.java"],
            expected_method=["findById", "getPets"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does creating a visit for a pet flow through the service layer",
            expected_file=["VisitRestControllerV1.java", "ClinicServiceImpl.java"],
            expected_method=["addVisit"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does the application retrieve a vet and their specialties",
            expected_file=["Vet.java", "VetRestControllerV1.java"],
            expected_method=["getVet", "getSpecialties"],
            adversarial=True,
        ),
        GoldenQuery(
            query="what is the relationship between Owner Pet and Visit",
            expected_file=["Owner.java", "Pet.java", "Visit.java"],
            adversarial=True,
        ),

        # ============================================================
        # HYBRID / SEMANTIC RETRIEVAL
        # ============================================================
        GoldenQuery(
            query="where does the application persist customer information",
            expected_file=[
                "Owner.java",
                "JdbcOwnerRepositoryImpl.java",
                "JpaOwnerRepositoryImpl.java",
            ],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does the system look up animal doctors",
            expected_file=["Vet.java", "VetRepository.java", "VetRestControllerV1.java"],
            adversarial=True,
        ),
        GoldenQuery(
            query="where is the logic for registering an animal under a customer",
            expected_file=["OwnerRestControllerV1.java", "Pet.java"],
            expected_method=["addPetToOwner"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does the application store appointments for animals",
            expected_file=["Visit.java", "VisitRestControllerV1.java"],
            expected_method=["addVisit"],
            adversarial=True,
        ),
        GoldenQuery(
            query="where is the database access code for pet owners",
            expected_file=["JdbcOwnerRepositoryImpl.java", "JpaOwnerRepositoryImpl.java"],
            expected_method=["findByLastName"],
            adversarial=True,
        ),

        # ============================================================
        # ADVERSARIAL / AMBIGUOUS
        # ============================================================
        GoldenQuery(
            query="find the pet repository implementation",
            expected_file=["JpaPetRepositoryImpl.java", "JdbcPetRepositoryImpl.java"],
            adversarial=True,
        ),
        GoldenQuery(
            query="find the owner repository implementation",
            expected_file=["JpaOwnerRepositoryImpl.java", "JdbcOwnerRepositoryImpl.java"],
            expected_method=["findById", "findByLastName"],
            adversarial=True,
        ),
        GoldenQuery(
            query="where is the vet repository",
            expected_file=[
                "VetRepository.java",
                "JpaVetRepositoryImpl.java",
                "JdbcVetRepositoryImpl.java",
            ],
            adversarial=True,
        ),
        GoldenQuery(
            query="how are owners handled by the REST API",
            expected_file=["OwnerRestControllerV1.java"],
            expected_method=["listOwners"],
            adversarial=True,
        ),
        GoldenQuery(
            query="where is pet creation implemented",
            expected_file=["OwnerRestControllerV1.java", "PetRestControllerV1.java"],
            expected_method=["addPetToOwner"],
            adversarial=True,
        ),

        # ============================================================
        # NEGATIVE QUERIES (correct behavior = "nothing relevant found")
        # ============================================================
        GoldenQuery(
            query="how are customer payments processed",
            expected_file=[],
            should_retrieve=False,
            adversarial=True,
        ),
        GoldenQuery(
            query="where is email notification implemented",
            expected_file=[],
            should_retrieve=False,
            adversarial=True,
        ),
        GoldenQuery(
            query="how are product discounts calculated",
            expected_file=[],
            should_retrieve=False,
            adversarial=True,
        ),
        GoldenQuery(
            query="where is the shopping cart implemented",
            expected_file=[],
            should_retrieve=False,
            adversarial=True,
        ),
        GoldenQuery(
            query="how are invoices generated",
            expected_file=[],
            should_retrieve=False,
            adversarial=True,
        ),

        # ============================================================
        # EDGE CASES
        # ============================================================
        GoldenQuery(
            query="how does the application handle an owner with no pets",
            expected_file=["JpaOwnerRepositoryImpl.java", "JdbcOwnerRepositoryImpl.java"],
            expected_method=["findByLastName", "findById"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how are invalid pet ids handled",
            expected_file=["PetRestControllerV1.java", "OwnerRestControllerV1.java"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how does the API handle a missing owner",
            expected_file=["JpaOwnerRepositoryImpl.java"],
            expected_method=["findById"],
            adversarial=True,
        ),
        GoldenQuery(
            query="where is validation performed when creating a visit",
            expected_file=["Visit.java", "VisitRestControllerV1.java"],
            adversarial=True,
        ),
        GoldenQuery(
            query="how are vet specialties updated",
            expected_file=["Vet.java"],
            expected_method=["setSpecialtiesInternal"],
            adversarial=True,
        ),
    ]


# ---- EXECUTION --------------------------------------------------------------
def failed_result(gq: GoldenQuery) -> QueryResult:
    return QueryResult(query=gq, hit=False, rank=-1, latency_ms=0.0, reason="no_response")


def run_query(gq: GoldenQuery, _retries: int = 2) -> QueryResult:
    params = {"message": gq.query}
    url = f"{BASE_URL}{SEARCH_ENDPOINT}?{urllib.parse.urlencode(params)}"

    response = None
    for attempt in range(MAX_RETRIES):
        start = time.perf_counter()
        try:
            response = requests.get(url, timeout=TIMEOUT_SECONDS, stream=True)
        except requests.RequestException as e:
            print(f"WARN — request error for {gq.query!r}: {e}")
            return failed_result(gq)

        if response.status_code == 429:
            wait = float(response.headers.get("Retry-After", 15))
            print(f"DEBUG — rate limited, waiting {wait}s (attempt {attempt + 1})")
            time.sleep(wait)
            continue

        if not response.ok:
            print(f"\nDEBUG — request failed for query: {gq.query!r}")
            print(f"DEBUG — status: {response.status_code}")
            print(f"DEBUG — body: {response.text}\n")
            return failed_result(gq)
        break
    else:
        print(f"WARN — rate limited after {MAX_RETRIES} retries: {gq.query!r}")
        return failed_result(gq)

    # SSE stream: each line is "data:<chunk>"; force UTF-8 to avoid garbled text
    response.encoding = "utf-8"
    chunks = []
    for line in response.iter_lines(decode_unicode=True):
        if line and line.startswith("data:"):
            chunks.append(line[len("data:"):])
    latency_ms = (time.perf_counter() - start) * 1000

    answer_text = "".join(chunks)
    print(f"DEBUG — answer for {gq.query!r}: {answer_text[:1000]}")
    
    if "unexpected error occurred while streaming" in " ".join(answer_text.lower().split()):
        if _retries > 0:
            print(f"WARN — server error, retrying after 30s: {gq.query!r}")
            time.sleep(30)
            return run_query(gq, _retries - 1)
        return failed_result(gq)

    # Negative query: pass only if GitGrok says nothing relevant was found
    normalized = " ".join(answer_text.lower().split())
    not_found = any(normalized.startswith(m) for m in NOT_FOUND_MARKERS)

    if not gq.should_retrieve:
        hit = not_found or any(m in normalized for m in NEGATIVE_EXTRA_MARKERS)
        return QueryResult(query=gq, hit=hit, rank=1 if hit else -1, latency_ms=latency_ms, reason="" if hit else "wrong_answer")

    if not_found:
        return QueryResult(query=gq, hit=False, rank=-1, latency_ms=latency_ms, reason="not_found")

    def name_in_text(name: str) -> bool:
        bare = name.replace(".java", "")
        return re.search(rf"\b{re.escape(bare)}\b", answer_text) is not None

    file_hit = any(name_in_text(f) for f in gq.expected_file)

    if gq.expected_method is None:
        hit = file_hit
    else:
        method_hit = any(name_in_text(m) for m in gq.expected_method)
        hit = file_hit or method_hit

    return QueryResult(query=gq, hit=hit, rank=1 if hit else -1, latency_ms=latency_ms, reason="" if hit else "wrong_answer")


def percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    index = max(0, min(len(sorted_values) - 1, int(round(pct / 100 * len(sorted_values))) - 1))
    return sorted_values[index]


def build_report(results: list[QueryResult]) -> EvalReport:
    total = len(results)
    hits = sum(1 for r in results if r.hit)
    # Exclude queries that never got a response (latency 0) from latency stats
    latencies = sorted(r.latency_ms for r in results if r.latency_ms > 0)
    accuracy = (hits / total * 100.0) if total else 0.0
    total_cost = total * (EMBEDDING_COST_PER_QUERY + LLM_COST_PER_QUERY)

    failures = [
        {
            "query": r.query.query,
            "expectedFile": r.query.expected_file,
            "expectedMethod": r.query.expected_method,
            "negative": not r.query.should_retrieve,
            "reason": r.reason,
        }
        for r in results
        if not r.hit
    ]

    return EvalReport(
        timestamp=datetime.now(timezone.utc).isoformat(),
        total_queries=total,
        hits=hits,
        accuracy=round(accuracy, 2),
        p50_latency=round(percentile(latencies, 50), 1),
        p95_latency=round(percentile(latencies, 95), 1),
        estimated_cost=round(total_cost, 5),
        failures=failures,
    )


def print_summary(report: EvalReport) -> None:
    print("\n=== GitGrok Eval Report ===")
    print(f"Queries run:   {report.total_queries}")
    print(f"Accuracy:      {report.accuracy}% ({report.hits}/{report.total_queries})")
    print(f"Latency p50:   {report.p50_latency}ms")
    print(f"Latency p95:   {report.p95_latency}ms")
    per_query = report.estimated_cost / report.total_queries if report.total_queries else 0
    print(f"Est. cost:     ${report.estimated_cost:.5f} total (${per_query:.5f}/query)")

    if report.failures:
        print("\nFailures:")
        for f in report.failures:
            kind = "NEGATIVE" if f["negative"] else "STANDARD"
            print(f'  [{kind}] "{f["query"]}" -> file: {f["expectedFile"]}, method: {f["expectedMethod"]}')


def main() -> None:
    queries = load_golden_queries()
    results = []
    for i, gq in enumerate(queries):
        print(f"[{i + 1}/{len(queries)}] {gq.query}")
        result = run_query(gq)
        results.append(result)
        if sum(1 for r in results if r.latency_ms == 0.0) > 3:
            print("ABORT: too many queries got no valid response (likely rate limited). Report not saved.")
            sys.exit(2)
        if i < len(queries) - 1:
            time.sleep(DELAY_BETWEEN_QUERIES)

    report = build_report(results)
    print_summary(report)
    
    in_scope = [r for r in results if not r.query.out_of_scope]
    in_scope_hits = sum(1 for r in in_scope if r.hit)
    print(f"In-scope accuracy: {in_scope_hits / len(in_scope) * 100:.2f}% ({in_scope_hits}/{len(in_scope)})")
    report.in_scope_accuracy = round(in_scope_hits / len(in_scope) * 100, 2)

    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        json.dump(asdict(report), f, indent=2)

    if report.accuracy < ACCURACY_THRESHOLD:
        print(f"\nFAIL: accuracy {report.accuracy}% below threshold {ACCURACY_THRESHOLD}%")
        sys.exit(1)


if __name__ == "__main__":
    main()
