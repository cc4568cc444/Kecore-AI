package com.cc.springai.config;

import com.cc.springai.embedding.OllamaLegacyEmbeddingModel;
import com.cc.springai.constants.SystemConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CommonConfiguration {
    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient.builder(model)
                .defaultSystem("你是一个猫娘，你的每句话结尾都会带喵")
                //环绕增强日志
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
    @Bean
    public ChatClient gameChatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient.builder(model)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                //环绕增强日志
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatClient financeChatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient.builder(model)
                .defaultSystem("""
                        你是金融年报 RAG 问答助手。你只能基于用户消息中的检索上下文回答 SEC 10-K 年报问题。
                        优先直接给出结论；涉及表格或财务计算时，必须使用上下文中的公司、财年、period、表头和行名。
                        如果上下文不足以确定答案，明确说明无法从当前上下文确定，不要编造。然后给出一个你认为可能的答案回答，并标记[来源不可靠]
                        结论回答保持简洁，最后依据来源要详细，最后给出一行依据来源，依据来源格式为`[序号]：<必须逐字复制 Context 中的连续文本，以及前后上下文信息>`。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatClient paperChatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient.builder(model)
                .defaultSystem("""
                        你是科研论文 RAG 问答助手。你只能基于用户消息中提供的论文检索上下文回答。
                        优先直接给出结论；涉及实验数字时，必须区分数据集、模型、指标、实验设置和表格列。
                        数字、单位、百分比和正负号必须忠实于上下文；需要计算时给出简洁公式。
                        如果上下文不足以确定答案，明确说明无法从当前论文库确定，不要使用外部知识补全。
                        回答最后必须列出证据，格式为 `[序号] 论文标题 — 章节：支持结论的原文摘录`。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatClient agentClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient.builder(model)
                .defaultSystem(SystemConstants.AGENT_SYSTEM_PROMPT)
                //环绕增强日志
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new SafeGuardAdvisor(List.of("温柔与风"), "换个话题聊聊吧", 0),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository){
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(200)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.ollama.base-url}") String baseUrl,
            @Value("${app.embedding.ollama.model}") String model,
            @Value("${app.embedding.ollama.dimensions}") int dimensions) {
        return new OllamaLegacyEmbeddingModel(baseUrl, model, dimensions);
    }
}
