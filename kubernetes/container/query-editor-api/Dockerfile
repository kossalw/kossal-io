FROM gcr.io/distroless/java21:latest
COPY out/api/assembly.dest/out.jar /app.jar
ENTRYPOINT ["java", "-cp", "/app.jar", "-Xms256M", "-Xmx1G", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseStringDeduplication", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M", "com.kossalw.api.MainServer"]