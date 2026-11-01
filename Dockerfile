# syntax=docker/dockerfile:1
#
# llm-switchyard 统一构建镜像。
# 多阶段构建：builder 阶段用 mvnw 打 exec.jar，runtime 阶段只带 JRE + 两个 jar。
# 刻意不 COPY 源码之外的任何东西，也不 COPY contract/ 与 docs/ —— 镜像越小，嵌入式网关越好卖。

# ---------- 构建阶段 ----------
FROM eclipse-temurin:22-jdk-jammy AS builder

WORKDIR /build

# 先只拷 pom 与 wrapper，利用 Docker 层缓存：依赖不变时不重新解析
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./
COPY pom.xml ./
COPY switchyard-core/pom.xml switchyard-core/
COPY switchyard-provider/pom.xml switchyard-provider/
COPY switchyard-starter/pom.xml switchyard-starter/
COPY switchyard-mock-llm/pom.xml switchyard-mock-llm/
COPY switchyard-sample/pom.xml switchyard-sample/
COPY LICENSE LICENSE-HEADER.txt ./

# 预热依赖（此步失败会让镜像构建失败，属预期行为：依赖解析不了就不该继续）
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DexcludeArtifactIds=switchyard-core,switchyard-provider,switchyard-starter,switchyard-mock-llm || true

COPY switchyard-core/src switchyard-core/src
COPY switchyard-provider/src switchyard-provider/src
COPY switchyard-starter/src switchyard-starter/src
COPY switchyard-mock-llm/src switchyard-mock-llm/src
COPY switchyard-sample/src switchyard-sample/src

# 打包两个可执行 jar（mock-llm 与 sample）；跳过测试，因为 CI 已跑过
RUN ./mvnw -B -q clean package -DskipTests -Dspotless.check.skip=true -Dlicense.skip=true -Djacoco.skip=true

# ---------- 运行阶段 ----------
FROM eclipse-temurin:22-jre-jammy AS runtime

# 中文场景：容器内必须有 UTF-8 与东八区，否则审计日志里的中文会变成问号
ENV LANG=C.UTF-8 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 以非 root 运行：安全基线，且能提前暴露镜像里的权限问题
RUN groupadd --system --gid 1001 switchyard \
 && useradd --system --uid 1001 --gid switchyard --home /app switchyard

COPY --from=builder /build/switchyard-mock-llm/target/switchyard-mock-llm-*-exec.jar /app/mock-llm.jar
COPY --from=builder /build/switchyard-sample/target/switchyard-sample-*-exec.jar /app/sample.jar

USER switchyard

# 18081 = mock-llm，18080 = sample
EXPOSE 18081 18080

# 默认启动 mock-llm；docker-compose 会按服务覆盖 command
CMD ["java", "-XX:MaxRAMPercentage=75", "-Dspring.config.name=mock-llm", "-jar", "/app/mock-llm.jar"]
