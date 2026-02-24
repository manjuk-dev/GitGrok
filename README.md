# GitGrok

Talk to your GitHub repository. Ask questions, get architect level answers.

![Java](https://img.shields.io/badge/Java-21+-orange?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Ollama](https://img.shields.io/badge/Ollama-Llama_3.2-black?style=flat-square)
![Pinecone](https://img.shields.io/badge/Pinecone-Serverless-00B4CC?style=flat-square)

---

## About

GitGrok is a specialized **RAG (Retrieval Augmented Generation)** application that lets you have a conversation with a GitHub repository's source code. Instead of searching for keywords, it understands the architecture and logic of a project by analyzing its actual files and answers like a senior software architect.

> **Local First AI Philosophy** — Your code never leaves your machine. The LLM runs fully locally via Ollama, ensuring complete privacy.

---

## How It Works

```
GitHub Repo
    |
    v
[ ETL Pipeline ]
  1. EXTRACT   -->  Fetch every .java file (recursive)
  2. TRANSFORM -->  Chunk into Documents + Metadata
  3. LOAD      -->  Store as vectors in Pinecone
    |
    v
User Query  -->  Similarity Search  -->  Ollama (Llama 3.2)  -->  Streaming Answer
```

---

## Tech Stack

| Component        | Technology            | Role                                         |
|------------------|-----------------------|----------------------------------------------|
| Framework        | Spring Boot 3.4       | Core application engine                      |
| AI Orchestration | Spring AI             | Connects LLM, Vector Store & ETL pipeline    |
| Local LLM        | Ollama (Llama 3.2)    | Processes code and generates answers locally |
| Vector Database  | Pinecone (Serverless) | Stores and searches semantic meaning of code |
| External API     | GitHub REST API       | Fetches files via the Recursive Trees API    |

---

## Features

**Recursive GitHub Scanning**
Uses the GitHub Git Trees API with `recursive=1` to traverse every sub-package and find every `.java` file across the entire repository, not just the root.

**Smart Code Ingestion**
Converts raw GitHub blobs into `Document` objects, preserving file paths as metadata so the AI always knows the exact source of any code snippet it references.

**Architect Level RAG**
Uses `QuestionAnswerAdvisor` with a custom Senior Architect prompt that forces the AI to provide step-by-step logic explanations and flag potential bugs with "Architect's Notes."

**Streaming Responses (SSE)**
The chat endpoint streams responses word-by-word via Server-Sent Events so you see answers as they are generated in real time.

---

## API Endpoints

### POST /api/v1/ingest/repo

Scans the repo, fetches all `.java` files, and populates Pinecone with vector embeddings.

```bash
curl -X POST http://localhost:8080/api/v1/ingest/repo \
  -H "Content-Type: application/json" \
  -d '{
    "owner": "danvega",
    "repo": "java-rag",
    "branch": "main"
  }'
```

### GET /api/v1/chat?message={query}

Performs a similarity search in Pinecone and returns a streaming AI response.

```bash
curl "http://localhost:8080/api/v1/chat?message=Explain+the+relationship+between+the+Controller+and+the+Service"
```

Example queries:
- "Explain the relationship between the Controller and the Service."
- "Where is the database connection configured?"
- "Are there any potential null pointer exceptions in the ingestion pipeline?"

---

## Roadmap

GitGrok is just getting started. Here is what is planned for future releases.

**Multilanguage Support**
Currently GitGrok only ingests `.java` files. The goal is to support any language like Python, TypeScript, Go, Rust making it useful for any codebase, not just JVM projects.

**Smarter Chunking**
Move beyond file level ingestion to method level and class level chunking. This means more precise vector search results and sharper answers when asking about a specific function or class.

**Conversation Memory**
Right now every question is stateless. Adding conversation history will allow followup questions like "what about the service you just mentioned?" without losing context between turns.

**Automatic Reingestion**
Watch a repository for new commits via GitHub Webhooks and automatically reembed changed files so the vector store never goes stale.

**Support for Private Repos**
Full OAuth flow so users can authenticate with their GitHub account and ingest private repositories without manually managing tokens.

**Web UI**
A clean browser based interface to ingest repos and chat with code without needing curl or a REST client.

**Model Flexibility**
Allow switching between different local models (Mistral, CodeLlama, Gemma) and cloud models (GPT-4, Claude) depending on the user's preference and hardware.

**Bug Detection Mode**
A dedicated scan mode that proactively reviews the entire codebase and surfaces potential issues — null pointer risks, missing error handling, security vulnerabilities — without needing to ask a specific question.

---

## Contributing

Contributions are welcome. Feel free to open an issue or submit a pull request.

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
