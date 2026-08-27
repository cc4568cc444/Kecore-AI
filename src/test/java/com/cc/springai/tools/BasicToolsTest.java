package com.cc.springai.tools;

import com.cc.springai.config.ShellToolConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BasicToolsTest {

    private final BasicTools tools = new BasicTools(new ShellToolConfiguration.ShellToolProperties());

    @Test
    void decodesGbkShellOutputWhenUtf8WouldBeMojibake() {
        byte[] bytes = "复制完成 周报-石磊.docx".getBytes(Charset.forName("GBK"));

        String decoded = ReflectionTestUtils.invokeMethod(tools, "decodeProcessOutput", bytes);

        assertThat(decoded).isEqualTo("复制完成 周报-石磊.docx");
    }

    @Test
    void decodesUtf16LittleEndianShellOutputWithoutBom() {
        byte[] bytes = "周报-石磊.docx".getBytes(StandardCharsets.UTF_16LE);

        String decoded = ReflectionTestUtils.invokeMethod(tools, "decodeProcessOutput", bytes);

        assertThat(decoded).isEqualTo("周报-石磊.docx");
    }

    @Test
    void routesCmdCommandsToCmdHostSoAmpersandsAreNotParsedByPowerShell() {
        ProcessBuilder processBuilder = ReflectionTestUtils.invokeMethod(
                tools,
                "windowsShellProcessBuilder",
                "cmd /c chcp 65001 > nul & copy \"D:\\E\\周报.docx\" \"%USERPROFILE%\\Desktop\\周报.docx\"");

        assertThat(processBuilder.command())
                .containsExactly(
                        "cmd.exe",
                        "/d",
                        "/s",
                        "/c",
                        "cmd /c chcp 65001 > nul & copy \"D:\\E\\周报.docx\" \"%USERPROFILE%\\Desktop\\周报.docx\"");
    }

    @Test
    void writesPowerShellScriptsWithUtf8BomForWindowsPowerShellCompatibility() throws Exception {
        Path script = Files.createTempFile("springai-basic-tools-", ".ps1");
        try {
            ReflectionTestUtils.invokeMethod(tools, "writeTextFile", script, "$src = 'D:\\E\\周报\\周报-石磊.docx'");

            byte[] bytes = Files.readAllBytes(script);

            assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
            assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                    .isEqualTo("$src = 'D:\\E\\周报\\周报-石磊.docx'");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void windowsPowerShellBootstrapSilencesProgressStreams() {
        String bootstrap = ReflectionTestUtils.invokeMethod(tools, "windowsPowerShellBootstrap");

        assertThat(bootstrap)
                .contains("$ProgressPreference='SilentlyContinue'")
                .contains("$VerbosePreference='SilentlyContinue'")
                .contains("$InformationPreference='SilentlyContinue'");
    }

    @Test
    void stripsPowerShellCliXmlProgressFromShellOutput() {
        String output = """
                exit: 0
                stdout:
                stderr:
                #< CLIXML
                <Objs Version="1.1.0.1" xmlns="http://schemas.microsoft.com/powershell/2004/04"><Obj S="progress" RefId="0"><MS><PR N="Record"><AV>正在准备首次使用模块。</AV><T>Completed</T></PR></MS></Obj></Objs>
                """;

        String sanitized = ReflectionTestUtils.invokeMethod(tools, "sanitizeShellOutput", output);

        assertThat(sanitized)
                .isEqualTo("""
                        exit: 0
                        stdout:
                        stderr:""");
    }
}
