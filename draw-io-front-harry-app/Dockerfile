# 基础镜像
FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.authors="xiaofuge"

# 配置
ENV PARAMS=""

# 时区
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 添加应用
ADD target/ai-agent-scaffold-app.jar /ai-agent-scaffold-app.jar

ENTRYPOINT ["sh","-c","java -jar $JAVA_OPTS /ai-agent-scaffold-app.jar $PARAMS"]
