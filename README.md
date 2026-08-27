# GitGrok

Talk to your GitHub repository. Ask questions, get architect-level answers.

![Java](https://img.shields.io/badge/Java-21+-orange?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Groq](https://img.shields.io/badge/Groq-GPT--OSS--120B-black?style=flat-square)
![Ollama](https://img.shields.io/badge/Ollama-Embeddings-black?style=flat-square)
![Pinecone](https://img.shields.io/badge/Pinecone-Serverless-00B4CC?style=flat-square)

---

## About

Reading through an unfamiliar codebase takes time. GitGrok solves this by combining **Hybrid Vector Search** with a **hosted LLM backend**, turning any repository into a queryable knowledge base that returns precise, architect-level answers.

The pipeline ingests code with intelligent chunking, generates hybrid vectors (dense semantic + sparse keyword) using local embeddings, and answers queries through Groq's hosted inference for fast, low-latency responses.

**Key Capabilities:**
- Hybrid vector search (semantic + keyword matching)
- Method-level code chunking (not just files)
- Multi-file relationship analysis
- Zero-inference mode (only references visible code, refuses to guess)
- Fast inference via Groq (sub-5-second typical responses)

---

## How It Works

```
GitHub Repo
    ↓
[ Intelligent ETL Pipeline ]
  • Recursive scan all .java files
  • Chunk by method, class, block
  • Generate dense vectors (semantic) via Ollama embeddings
  • Generate sparse vectors (code-aware keywords)
  • Store in Pinecone with metadata
    ↓
User Query
    ↓
[ Hybrid Search ]
  • Extract target files (1-N files)
  • Dense search: semantic similarity
  • Sparse search: code-aware keywords
  • Combine & rank results (α=0.6)
  • Filter by target filename
    ↓
Groq (Hosted LLM) → Streaming Answer
```

---

## Tech Stack

| Component | Technology | Role |
|-----------|-----------|------|
| Framework | Spring Boot 3.4 | Application engine |
| AI Orchestration | Spring AI 1.1.5 | LLM & Vector Store integration |
| Chat LLM | Groq (`openai/gpt-oss-120b`) | Hosted inference for answering queries |
| Embedding Model | Ollama (`nomic-embed-text`) | Local embedding generation for retrieval |
| Vector Database | Pinecone (Serverless) | Hybrid vector storage & search |
| Code Repository | GitHub REST API | Recursive file fetching |

> **Note on architecture:** Chat inference runs on Groq's hosted API for speed; embeddings still run locally via Ollama, since Groq does not offer an embeddings endpoint. This is a deliberate hybrid setup, not a partial migration.

---

## Core Features

### Hybrid Vector Search

Combines two search approaches for accuracy:

**Dense Vectors (Semantic)**
- Understands meaning: "What does this do?"
- Catches intent and context
- Identifies similar concepts

**Sparse Vectors (Code-Aware Keywords)**
- Exact keyword matches: "getName()"
- Code-aware term weighting (methods, classes, properties)
- Method & class name precision

**Combined (Best of Both)**
```
Final Score = (60% Dense) + (40% Sparse Keyword)
            = Semantic understanding + Exact precision
```

### Method-Level Chunking

Breaks code into meaningful units with intelligent term weighting:

```
Java File
  ├─ Method 1 (with class context)
  ├─ Method 2 (with class context)
  ├─ Class declarations
  ├─ Configuration blocks
  └─ Control flow segments

For each chunk, generate:
✓ Dense vectors (semantic meaning)
✓ Sparse vectors with code-aware term frequency:
    - Methods (getId, setName) → 3.0x weight
    - Classes (Owner, Pet) → 2.5x weight
    - Properties (age, email) → 2.0x weight
    - Keywords (if, for) → 0.3x weight (penalized)
    - Stop words (the, and) → 0.1x weight (skipped)
```

**Why It Matters:**
- More precise search (method-level, not file-level)
- Code-aware weighting ensures methods rank higher than keywords
- Better semantic meaning per chunk
- Faster retrieval with smaller context
- Accurate code references in responses

### Multi-File Analysis

Analyze relationships between files naturally with smart file extraction that recognizes:
- `file X.java` → Extracts "X.java"
- `from Repository` → Extracts "Repository.java"
- `X.java and Y.java` → Extracts both
- `X class methods` → Extracts "X.java"

> File extraction filters out common command verbs (List, Show, Give, What, etc.) to avoid false-positive matches on ordinary English words at the start of a query.

### Zero-Inference Mode

System prompt enforces strict rules:

```
✗ Never invent method signatures
✗ Never fabricate REST endpoints
✗ Never assume patterns without evidence
✗ Never claim a class implements an interface without an explicit
  implements/extends declaration in the visible snippet
✓ Only reference visible snippets
✓ Mark incomplete information clearly ("Snippet ends prematurely")
✓ Distinguish "file not found" from "file found, detail not visible"
✓ Ask for clarification when ambiguous
```

---

## API Endpoints

### POST /api/v1/ingest/repo

Scans GitHub, chunks intelligently, generates hybrid vectors, loads to Pinecone.

```bash
curl -X POST http://localhost:8080/api/v1/ingest/repo \
  -H "Content-Type: application/json" \
  -d '{
    "owner": "spring-petclinic",
    "repo": "spring-petclinic-rest",
    "branch": "master"
  }'
```

Processing:
1. Recursively fetch all `.java` files from `src/main` (filters out test files)
2. Chunk by method, class, logical blocks
3. Generate dense embeddings via Ollama (semantic meaning)
4. Generate sparse embeddings (code-aware term weighting)
5. Store with metadata (filename, class, method)

> **Note:** Only source code from `src/` is ingested. Test files (`src/test`) are excluded to keep the knowledge base focused on production code.

### GET /chat?message={query}

Hybrid search + file extraction + streaming response via Groq.

```bash
curl "http://localhost:8080/chat?message=What+does+VetRestControllerV1.java+do?"
```

Processing:
1. Extract target files from query
2. Hybrid search (dense + sparse vectors)
3. Filter by target filenames
4. Pass top chunks to Groq for inference
5. Stream response (Server-Sent Events)

---

## Getting Started

### Prerequisites

```bash
# Local embedding model (still required — Groq has no embeddings API)
ollama pull nomic-embed-text
ollama serve

# Groq API key (free tier available)
# https://console.groq.com

# Pinecone (free tier)
# https://www.pinecone.io/

# Java 21+
java -version
```

### Configuration

Two Spring profiles are supported:

- **`local`** — full Ollama setup (chat + embeddings), useful for offline development or as a fallback if Groq is unavailable.
- **`prod`** — Groq for chat inference, Ollama for embeddings only.

```bash
# Local (Ollama chat + embeddings)
java -jar target/gitgrok-*.jar --spring.profiles.active=local

# Prod (Groq chat, Ollama embeddings)
java -jar target/gitgrok-*.jar --spring.profiles.active=prod
```

Set the following environment variables for the `prod` profile:
```
GROQ_API_KEY=your_groq_key
PINECONE_API_KEY=your_pinecone_key
GITHUB_PAT=your_github_token
```

### Ingest & Run

```bash
mvn clean package -DskipTests
java -jar target/gitgrok-*.jar --spring.profiles.active=prod
```

Ingest a repository via POST `/api/v1/ingest/repo` and start querying with GET `/chat`.

---

## Roadmap

**Next Releases**
- [ ] Conversation memory (multi-turn chats)
- [ ] Automatic re-indexing on new commits
- [ ] Private repository OAuth
- [ ] Multi-language support (Python, TypeScript, Go)
- [ ] AST-driven code graphs for dependency tracing
- [ ] Commit history ingestion for architectural "why" questions

---

## Contributing

Contributions welcome! Help with:
- Chunking strategy improvements
- Additional language support
- Vector search optimization
- UI/UX development

1. Fork the project
2. Create feature branch (`git checkout -b feature/amazing`)
3. Commit (`git commit -m 'Add feature'`)
4. Push (`git push origin feature/amazing`)
5. Open Pull Request

---

## License

MIT License — See LICENSE file

---

## Resources

- [Spring AI Documentation](https://github.com/spring-projects/spring-ai)
- [Pinecone Hybrid Search Guide](https://docs.pinecone.io/guides/data-types/hybrid-search)
- [Groq Documentation](https://console.groq.com/docs)
- [Ollama Models](https://ollama.ai/library)

---

**Last Updated:** August 2026
