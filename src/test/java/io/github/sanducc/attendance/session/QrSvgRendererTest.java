package io.github.sanducc.attendance.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class QrSvgRendererTest {

    private static final String URL =
            "http://localhost/a/7d7a3c8e-31a4-4a59-9f5e-0a8f3b4c2d11?t=AbCdEfGhIjKlMnOpQrStUv";

    private final QrSvgRenderer renderer = new QrSvgRenderer();

    @Test
    void rendersASquareSvgWithViewBoxAndOnePath() throws Exception {
        String svg = renderer.render(URL);

        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        Element root = parse(svg).getDocumentElement();
        assertThat(root.getNodeName()).isEqualTo("svg");
        assertThat(root.getAttribute("xmlns")).isEqualTo("http://www.w3.org/2000/svg");

        String[] viewBox = root.getAttribute("viewBox").split(" ");
        assertThat(viewBox).hasSize(4);
        assertThat(viewBox[0]).isEqualTo("0");
        assertThat(viewBox[1]).isEqualTo("0");
        int width = Integer.parseInt(viewBox[2]);
        int height = Integer.parseInt(viewBox[3]);
        assertThat(width).isEqualTo(height);
        // QR versions are 21..177 modules wide, plus the 2-module quiet zone on each side.
        assertThat((width - 4 - 21) % 4).isZero();
        assertThat(width).isBetween(25, 181);

        assertThat(root.getElementsByTagName("path").getLength()).isEqualTo(1);
        String d = ((Element) root.getElementsByTagName("path").item(0)).getAttribute("d");
        assertThat(d).isNotBlank().matches("(M\\d+ \\d+h\\d+v1h-\\d+z)+");
    }

    @Test
    void containsNoScriptStyleOrEventHandlers() {
        String svg = renderer.render(URL).toLowerCase();

        assertThat(svg).doesNotContain("<script", "<style", "javascript:", "onload", "onclick", "<foreignobject");
        // The content itself (the URL) is not embedded as text in the SVG.
        assertThat(svg).doesNotContain("?t=");
    }

    @Test
    void isDeterministicForTheSameContentAndDiffersForOtherContent() {
        assertThat(renderer.render(URL)).isEqualTo(renderer.render(URL));
        assertThat(renderer.render(URL)).isNotEqualTo(renderer.render(URL + "x"));
    }

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
