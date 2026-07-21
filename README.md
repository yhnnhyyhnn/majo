# Agent Coding — Day 1 最小原型

Spring Boot + AgentScope + OpenAI-compatible API

## 配置

编辑 `src/main/resources/application.yml`：

```yaml
agentscope:
  openai:
    api-key: sk-your-key-here
    base-url: https://your-proxy.com/v1
    model-name: gpt-4o-mini
```

## 运行

```powershell
mvn spring-boot:run
```

## 验证

```
> 你好
> 读一下 pom.xml
> /quit
```
