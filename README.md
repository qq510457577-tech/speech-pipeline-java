# Speech Pipeline Java

Spring Boot service for real-time speech recognition and text-to-speech.

## Features

- Aliyun NLS real-time ASR over REST and WebSocket
- Boson AI Higgs Audio v3 TTS
- Static browser UI under `/speech`
- Health and status endpoints under `/api`

## Runtime Configuration

The service reads credentials from environment variables:

- `ALI_ACCESS_KEY_ID`
- `ALI_ACCESS_KEY_SECRET`
- `ALI_APP_KEY`
- `BOSON_API_KEY`

For systemd deployments, keep these values in an `EnvironmentFile` outside git.

## Build

```bash
mvn -DskipTests package
```

## Run

```bash
java -Dspring.config.location=file:src/main/resources/application.yml \
  -jar target/speech-pipeline-1.0.0.jar
```

The default HTTP port is `5003`.
