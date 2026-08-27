# Docker Packaging

## Build Image

```powershell
docker build -t springai:local .
```

The Docker build skips test compilation with `-Dmaven.test.skip=true` because the current test sources still reference older memory APIs.

## Run With Docker Compose

```powershell
$env:MIMO_KEY="your-model-api-key"
docker-compose up --build
```

Open:

```text
http://localhost:8088
```

## Services

- `app`: Spring Boot application, exposed on `8088`.
- `postgres`: PostgreSQL 16 with pgvector, exposed on `5432`.

The database data is stored in the `springai-postgres` Docker volume.

## Important Environment Variables

```text
MIMO_KEY                         model API key
SPRING_DATASOURCE_URL            default: jdbc:postgresql://postgres:5432/spring_agent
SPRING_DATASOURCE_USERNAME       default: postgres
SPRING_DATASOURCE_PASSWORD       default: 123
SPRING_AI_MCP_CLIENT_ENABLED     default: false
UTOOLS_MCP_KEY                   optional uTools MCP key
MY_COFFEE_MCP_AUTHORIZATION      optional Luckin Coffee MCP Authorization header
APP_EMBEDDING_OLLAMA_BASE_URL    default: http://host.docker.internal:11434
APP_FINANCIAL_RAG_RERANK_URL     default: http://host.docker.internal:8010
```

## MCP In Docker

The `docker` Spring profile disables MCP by default:

```text
SPRING_AI_MCP_CLIENT_ENABLED=false
```

This is intentional because the default local MCP configuration contains Windows host paths such as Python and calculator MCP paths. Enable MCP only after replacing those paths with container-accessible services or container-side commands.

## Verified Locally

```powershell
mvn '-Dmaven.repo.local=target/m2' '-Dmaven.test.skip=true' -q package
docker-compose config
```

`docker build` requires Docker Desktop / Docker daemon to be running.
