package com.cc.springai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UserInteractionTools {

    @Tool(description = """
            当任务确实需要用户补充信息才能继续时调用。
            这是唯一的用户询问工具：既可用于单选，也可用于短文本补充。
            如果提供了 options，前端会把这些选项显示为可点击按钮，并自动追加最后一项“其他”供用户手动输入。
            如果没有可选项，前端只显示输入框。
            只在关键阻塞信息上使用，不要为了非关键细节频繁打扰用户。
            问题应该简短，不能包含任何可能的例子、子选项或列表，因为这些子选项应该放到options中。
            """)
    public String askUser(
            @ToolParam(description = "要展示给用户的简短问题") String question,
            @ToolParam(description = "可选项列表，每个option必须使用Emoji开头；如果为空则让用户直接输入", required = false) List<String> options,
            @ToolParam(description = "输入框占位提示；没有则传空字符串", required = false) String placeholder,
            @ToolParam(description = "补充说明；没有则传空字符串", required = false) String description) {
        return "等待用户回答。";
    }
}
