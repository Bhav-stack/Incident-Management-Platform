# AWS deployment (stretch phase)

Target: ECS Fargate with MSK (Kafka), RDS PostgreSQL, and ElastiCache Redis.
This directory holds the deployment artifacts; the actual push requires your
AWS account and CLI credentials.

## Layout

| File | What it is |
|---|---|
| `task-definition-*.json` | ECS Fargate task definitions, one per service |
| `../../deploy/Dockerfile.*` | Container images (Gradle build stage + JRE runtime) |
| `../../deploy/nginx.conf` | Dashboard image: static bundle + API/WS reverse proxy |

## 1. Build and push images

```bash
aws ecr create-repository --repository-name aegis/simulator
aws ecr create-repository --repository-name aegis/ingest
aws ecr create-repository --repository-name aegis/incident
aws ecr create-repository --repository-name aegis/dashboard

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGION=us-east-1
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.$REGION.amazonaws.com

docker build -f deploy/Dockerfile.simulator -t aegis/simulator:latest .
docker tag aegis/simulator:latest $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/aegis/simulator:latest
docker push $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/aegis/simulator:latest
# repeat for ingest, incident, dashboard
```

## 2. Infrastructure

- **MSK** cluster (Kafka 3.x, 3 brokers) or Amazon MQ for Kafka; note the
  bootstrap brokers.
- **RDS PostgreSQL 16** with the `aegis` user/password; run the Flyway
  migrations by starting incident-svc/ingest-svc (they migrate on boot).
  Create the `aegis_ingest` database via the RDS initial DB or a post-boot
  statement (see `postgres-init/01-databases.sql`).
- **ElastiCache Redis 7** cluster (kill switches, dedup, locks).
- **ALB** routing `/api/*` and `/ws/*` to the incident-svc target group
  (enable sticky sessions for SockJS), `/` to the dashboard target group.

## 3. Register task definitions and create services

```bash
aws ecs register-task-definition --cli-input-json file://deploy/aws/task-definition-incident.json
# ... same for simulator, ingest, dashboard (update image URIs and env first)
```

Task definitions use the `aegis` execution role; create it once with
`AmazonECSTaskExecutionRolePolicy` and (for pulling images) ECR read access.
Replace `REPLACE_ME_*` placeholders with real values: MSK bootstrap servers,
RDS/ElastiCache endpoints, `OPENAI_API_KEY` (agent mode) or `AGENT_FAKE_LLM=true`.

## 4. Env wiring

| Service | Key env vars |
|---|---|
| simulator | `KAFKA_BOOTSTRAP_SERVERS` |
| ingest-svc | `KAFKA_BOOTSTRAP_SERVERS`, `POSTGRES_HOST/PORT/DB/USER/PASSWORD` (aegis_ingest DB), `REDIS_HOST/PORT` |
| incident-svc | `KAFKA_BOOTSTRAP_SERVERS`, `POSTGRES_*`, `REDIS_HOST/PORT`, `SIMULATOR_BASE_URL`, `AGENT_MODE`, `OPENAI_API_KEY` or `AGENT_FAKE_LLM` |
| dashboard | `INCIDENT_SVC` upstream (nginx) |

## Notes

- The two Postgres databases (`aegis`, `aegis_ingest`) live on one RDS
  instance for the demo; split them per service for production.
- SockJS over an ALB works best with the `/ws/` path routed with
  WebSocket-enabled target group attributes and idle timeout >= 3600s.
- The dashboard nginx config reads `INCIDENT_SVC` at container start
  (use the nginx image entrypoint with envsubst if you need templating).