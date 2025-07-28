# Use a minimal base image for Kotlin applications
FROM openjdk:17-slim-bullseye AS builder

# Set the working directory
WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle/ gradle/
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
COPY gradle.properties gradle.properties

# Copy source code
COPY src/ src/

# Grant execution permission to the Gradle wrapper
RUN chmod +x gradlew

# Build the application
RUN ./gradlew build --no-daemon

# Create a new stage for the final image
FROM openjdk:17-slim-bullseye

# Set the working directory
WORKDIR /app

# Copy the built plugin JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose the necessary port (if applicable)
# EXPOSE 8080

# Run the application as a non-root user
RUN addgroup --system appgroup && adduser --system appuser --ingroup appgroup
USER appuser

# Command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]