# 多阶段构建：构建镜像 ~700MB，运行镜像只带 JRE
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存把依赖下下来（改代码不会重下依赖）
# 用阿里云镜像加速，国内构建快很多
RUN mkdir -p /root/.m2 \
 && printf '%s\n' \
    '<settings><mirrors><mirror>' \
    '<id>aliyun</id><mirrorOf>central</mirrorOf>' \
    '<url>https://maven.aliyun.com/repository/public</url>' \
    '</mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行
RUN useradd -r -u 1001 -m appuser
COPY --from=build /build/target/*.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
ENV TZ=Asia/Shanghai

# 健康检查：容器内没有 curl，用 wget（temurin 镜像自带 bash，可从 /dev/tcp 探测）
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
