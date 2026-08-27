// package com.cc.springai.service;
//
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.io.TempDir;
//
// import java.nio.file.Files;
// import java.nio.file.Path;
//
// import static org.assertj.core.api.Assertions.assertThat;
//
// class MemoryServiceTest {
//
//     private MemoryService memoryService;
//
//     @TempDir
//     Path tempDir;
//
//     @BeforeEach
//     void setUp() {
//         memoryService = new MemoryService(tempDir);
//     }
//
//     @Test
//     void appendProjectMemoryDoesNotDuplicateExistingMemoryCopiedFromPrompt() throws Exception {
//         String first = memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 cc",
//                 "用户明确要求记住名字，以便后续对话中称呼用户"
//         );
//         String duplicate = memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 cc（原因：用户明确要求记住名字，以便后续对话中称呼用户）",
//                 "用户明确要求记住名字，以便后续对话中称呼用户"
//         );
//
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(first).contains("已保存");
//         assertThat(duplicate).contains("未重复保存");
//         assertThat(memory).containsOnlyOnce("用户的名字是 cc");
//     }
//
//     @Test
//     void appendProjectMemoryCleansReasonFromContentBeforeWriting() throws Exception {
//         memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "- 2026-05-26: 用户希望以后每句话结尾加上喵~（原因：用户明确要求长期输出偏好）",
//                 "用户明确要求长期输出偏好"
//         );
//
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(memory).contains("用户希望以后每句话结尾加上喵~");
//         assertThat(memory).doesNotContain("2026-05-26: 2026-05-26");
//         assertThat(memory).doesNotContain("喵~（原因：用户明确要求长期输出偏好）（原因：");
//     }
//
//     @Test
//     void appendProjectMemoryAcceptsPreference() throws Exception {
//         String result = memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户希望以后每句话结尾加上喵~",
//                 "用户明确要求长期输出偏好"
//         );
//
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(result).contains("已保存");
//         assertThat(memory).contains("用户希望以后每句话结尾加上喵~");
//     }
//
//     @Test
//     void forgetProjectMemoryRemovesRequestedExistingFact() throws Exception {
//         memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 cc",
//                 "用户要求记住名字"
//         );
//
//         String result = memoryService.forgetProjectMemory(
//                 tempDir.toString(),
//                 "cc",
//                 "用户明确要求遗忘名字"
//         );
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(result).contains("已从全局 MEMORY.md 删除 1 条");
//         assertThat(memory).doesNotContain("cc");
//     }
//
//     @Test
//     void replaceProjectMemoryReplacesOldFactWhenUserProvidesNewValue() throws Exception {
//         memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 cc",
//                 "用户要求记住名字"
//         );
//
//         String result = memoryService.replaceProjectMemory(
//                 tempDir.toString(),
//                 "cc",
//                 "Preferences",
//                 "用户的名字是 newName",
//                 "用户明确更正名字"
//         );
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(result).contains("已在全局 MEMORY.md 中替换 1 条");
//         assertThat(memory).doesNotContain("用户的名字是 cc");
//         assertThat(memory).contains("用户的名字是 newName");
//     }
//
//     @Test
//     void replaceProjectMemoryAcceptsNewNameWithoutCorrectionKeyword() throws Exception {
//         memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 磊落不凡",
//                 "用户要求记住名字"
//         );
//
//         String result = memoryService.replaceProjectMemory(
//                 tempDir.toString(),
//                 "磊落不凡",
//                 "Preferences",
//                 "用户的名字是 cc",
//                 "用户提供了新名字"
//         );
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(result).contains("已在全局 MEMORY.md 中替换 1 条");
//         assertThat(memory).doesNotContain("用户的名字是 磊落不凡");
//         assertThat(memory).contains("用户的名字是 cc");
//     }
//
//     @Test
//     void forgetProjectMemoryDoesNotDependOnSpecificIntentKeywords() throws Exception {
//         memoryService.appendProjectMemory(
//                 tempDir.toString(),
//                 "Preferences",
//                 "用户的名字是 cc",
//                 "用户要求记住名字"
//         );
//
//         String result = memoryService.forgetProjectMemory(
//                 tempDir.toString(),
//                 "cc",
//                 "删除姓名信息"
//         );
//         String memory = Files.readString(tempDir.resolve("MEMORY.md"));
//
//         assertThat(result).contains("已从全局 MEMORY.md 删除 1 条");
//         assertThat(memory).doesNotContain("cc");
//     }
//
//     @Test
//     void automaticMemoryIsGlobalAcrossWorkingDirectories() throws Exception {
//         Path workspaceA = Files.createDirectories(tempDir.resolve("workspace-a"));
//         Path workspaceB = Files.createDirectories(tempDir.resolve("workspace-b"));
//
//         memoryService.appendProjectMemory(
//                 workspaceA.toString(),
//                 "Preferences",
//                 "用户偏好使用中文回复",
//                 "用户长期偏好"
//         );
//
//         assertThat(workspaceA.resolve("MEMORY.md")).doesNotExist();
//         assertThat(workspaceB.resolve("MEMORY.md")).doesNotExist();
//         assertThat(Files.readString(tempDir.resolve("MEMORY.md"))).contains("用户偏好使用中文回复");
//
//         String promptMemory = memoryService.loadPromptMemory(workspaceB.toString());
//         assertThat(promptMemory).contains("全局自动记忆");
//         assertThat(promptMemory).contains("用户偏好使用中文回复");
//     }
//
// }
