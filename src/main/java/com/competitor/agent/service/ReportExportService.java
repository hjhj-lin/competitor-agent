package com.competitor.agent.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ReportExportService {

    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    public ReportExportService() {
        MutableDataSet options = new MutableDataSet();
        this.markdownParser = Parser.builder(options).build();
        this.htmlRenderer = HtmlRenderer.builder(options).build();
    }

    /**
     * 将Markdown报告导出为PDF字节数组
     */
    public byte[] exportPdf(String companyName, String markdownContent) throws IOException {
        String htmlBody = markdownToHtml(markdownContent);
        String fullHtml = wrapHtml(companyName, htmlBody);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(fullHtml, null);
        builder.toStream(outputStream);
        builder.run();

        log.info("PDF导出完成, 公司={}, 大小={}bytes", companyName, outputStream.size());
        return outputStream.toByteArray();
    }

    private String markdownToHtml(String markdown) {
        Node document = markdownParser.parse(markdown);
        return htmlRenderer.render(document);
    }

    private String wrapHtml(String companyName, String bodyHtml) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8"/>
            <style>
                body { font-family: SimSun, serif; font-size: 12pt; line-height: 1.8; margin: 40px; }
                h1 { font-size: 20pt; text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; }
                h2 { font-size: 16pt; color: #2c3e50; margin-top: 24px; }
                h3 { font-size: 14pt; color: #34495e; }
                table { border-collapse: collapse; width: 100%; margin: 12px 0; }
                th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
                th { background-color: #f5f5f5; font-weight: bold; }
                blockquote { border-left: 4px solid #ddd; padding-left: 16px; color: #666; margin: 12px 0; }
                code { background-color: #f4f4f4; padding: 2px 6px; border-radius: 3px; font-size: 10pt; }
                .cover { text-align: center; margin-bottom: 40px; }
                .cover h1 { border: none; font-size: 24pt; }
                .cover p { color: #666; font-size: 12pt; }
            </style>
            </head>
            <body>
            <div class="cover">
                <h1>""" + companyName + """
            竞品分析报告</h1>
            </div>
            """ + bodyHtml + """
            </body>
            </html>""";
    }
}
