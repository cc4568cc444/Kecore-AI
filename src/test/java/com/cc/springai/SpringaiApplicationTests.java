package com.cc.springai;

import com.cc.springai.utils.VectorUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.fasterxml.jackson.databind.cfg.CoercionInputShape.Array;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SpringaiApplicationTests {

	// springai 已经自动注入过了
    @Autowired
    private EmbeddingModel embeddingModel;
    
    @Autowired
    private VectorStore vectorStore;

    @Test
    void calculatesDistancesBetweenTextAndCandidates() {
        String query = "钱怎么退";
        String[] candidates = {
                "退款流程",
                "学java就到黑马程序员",
				"学python就到尚硅谷",
                "黑马程序员提供Java编程课程",
                "今天天气晴朗，适合散步",
        };

        float[] queryVector = embeddingModel.embed(query);
        double[] distances = new double[candidates.length];

        for (int index = 0; index < candidates.length; index++) {
            float[] candidateVector = embeddingModel.embed(candidates[index]);
            distances[index] = VectorUtils.euclideanDistance(queryVector, candidateVector);
            System.out.printf("query='%s', candidate='%s', euclideanDistance=%.6f%n",
                    query, candidates[index], distances[index]);
        }

        assertThat(distances[0]).isGreaterThan(0.0);
        assertThat(distances[0]).isLessThanOrEqualTo(distances[1]);
        assertThat(distances[0]).isLessThanOrEqualTo(distances[2]);
		assertThat(distances[0]).isLessThanOrEqualTo(distances[3]);
    }
    
    @Test
    public void testVectorStore(@TempDir Path tempDir) throws IOException {
        // 先把项目中的 pom.xml 转换成临时 PDF，避免测试依赖外部 PDF 文件
        Path pdfPath = convertPomToPdf(tempDir);

        // 创建 PDF 读取器，Spring AI 会把 PDF 页面内容读取成 Document
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(new FileSystemResource(pdfPath));
        
        // 读取 PDF 文档内容
        List<Document> documents = pdfReader.get();

        // 按 token 数量拆分 Document，避免单个文档块过大影响向量化和检索效果
        List<Document> splitDocuments = TokenTextSplitter.builder()
                .withChunkSize(100)
                .withMinChunkLengthToEmbed(1)
                .build()
                .split(documents);
        
        // 写入向量库：VectorStore 会调用 EmbeddingModel 生成向量并保存文档块
        vectorStore.add(splitDocuments);
        
        // 相似度搜索：根据查询文本生成向量，然后从向量库中找最相近的文档块
        List<Document> searchResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query("spring ai pdf document reader dependency")
                .topK(5)
                .similarityThresholdAll()
                .build());

        // 打印相似片段结果，方便在测试控制台中观察向量检索召回了哪些内容
        for (int index = 0; index < searchResults.size(); index++) {
            Document document = searchResults.get(index);
            System.out.printf("""
                    
                    ===== 相似片段 %d =====
                    score: %s
                    metadata: %s
                    text:
                    %s
                    """,
                    index + 1,
                    document.getScore(),
                    document.getMetadata(),
                    document.getText());
        }

        // 验证 PDF 已被读取、拆分、写入，并能通过语义搜索召回目标依赖内容
        assertThat(documents).isNotEmpty();
        assertThat(splitDocuments).isNotEmpty();
        assertThat(searchResults).isNotEmpty();
        assertThat(searchResults)
                .anySatisfy(document -> assertThat(document.getText()).contains("spring-ai-pdf-document-reader"));
    }

    private Path convertPomToPdf(Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("pom.pdf");
        List<String> lines = Files.readAllLines(Path.of("pom.xml"), StandardCharsets.UTF_8);

        try (PDDocument document = new PDDocument()) {
            PDPageContentStream contentStream = startNewPage(document);
            int lineCount = 0;

            for (String line : lines) {
                if (lineCount == 64) {
                    contentStream.endText();
                    contentStream.close();
                    contentStream = startNewPage(document);
                    lineCount = 0;
                }

                contentStream.showText(line.replace('\t', ' '));
                contentStream.newLine();
                lineCount++;
            }

            contentStream.endText();
            contentStream.close();
            document.save(pdfPath.toFile());
        }

        return pdfPath;
    }

    private PDPageContentStream startNewPage(PDDocument document) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);

        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        contentStream.beginText();
        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 9);
        contentStream.setLeading(11);
        contentStream.newLineAtOffset(40, 760);
        return contentStream;
    }
   
    public void quickSort(int[] array, int left, int right){
        if(left>=right)return;
        int num = array[left];
        int i = left;
        int j = right;
        while(i<j){
            while(j>i && array[j]>=num){
                j--;
            }
            while(i<j && array[i]<=num){
                i++;
            }
            int t = array[j];
            array[j] = array[i];
            array[i] = t;
        }
        array[left] = array[i];
        array[i] = num;
        quickSort(array, left,i-1);
        quickSort(array, i+1,right);
    }
    @Test
    void testQuickSort(){
        int[] a = new int[]{4,3,2,5,8};
        quickSort(a, 0, 4);
        for(var x:a){
            System.out.print(x+",");
        }
        System.out.println();
    }
    // 2,4,3
    
    
}
