package com.cloudbees.jenkins.support.configfiles;

import com.cloudbees.plugins.credentials.SecretBytes;
import hudson.util.Secret;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.helpers.XMLFilterImpl;
import org.xml.sax.helpers.XMLReaderFactory;

/**
 * Secret Handler for xml files to add to the support bundle.
 * We want to use a placeholder instead of Secrets.
 */
class SecretHandler {

    /**
     * our placeholder
     */
    protected static final String SECRET_MARKER = "#secret#";
    public static final String OUTPUT_ENCODING = "UTF-8";
    public static final Pattern SECRET_PATTERN = Pattern.compile(">\\{(.*)\\}<|>(.*)\\=<");

    /**
     * find the secret in the xml file and replace it with the place holder
     * @param xmlFile we want to parse
     * @return the patched xml content with redacted secrets
     * @throws SAXException if some XML parsing issue occurs.
     * @throws IOException if some issue occurs while reading the providing file.
     * @throws TransformerException if an issue occurs while writing the result.
     */
    public static String findSecrets(File xmlFile) throws SAXException, IOException, TransformerException {

        XMLReader xr = new XMLFilterImpl(XMLReaderFactory.createXMLReader()) {
            private String tagName = "";

            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts)
                    throws SAXException {
                tagName = qName;
                super.startElement(uri, localName, qName, atts);
            }

            public void endElement(String uri, String localName, String qName) throws SAXException {
                tagName = "";
                super.endElement(uri, localName, qName);
            }

            @Override
            public void characters(char[] ch, int start, int length) throws SAXException {
                if (!"".equals(tagName)) {
                    String value = new String(ch, start, length).trim();
                    //if it's a secret, then use a place holder
                    // convenience check !"{}".equals(value) because of JENKINS-47500
                    if (!"".equals(value) && !"{}".equals(value)) {
                        if ((Secret.decrypt(value)) != null || SecretBytes.isSecretBytes(value)) {
                            ch = SECRET_MARKER.toCharArray();
                            start = 0;
                            length = ch.length;
                        }
                    }
                }
                super.characters(ch, start, length);
            }
        };
        String str = FileUtils.readFileToString(xmlFile);
        Source src = new SAXSource(xr, new InputSource(new StringReader(str)));
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        Result res = new StreamResult(result);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        //omit xml declaration because of https://bugs.openjdk.java.net/browse/JDK-8035437
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, OUTPUT_ENCODING);

        try {
            transformer.transform(src, res);
            return result.toString("UTF-8");
        } catch (TransformerException e) {
            return findSecretFallback(str);
        }
    }


    private static String findSecretFallback(String xml) {
        Matcher matcher = SECRET_PATTERN.matcher(xml);
        while(matcher.find()) {
            String secret = matcher.group();
            if(secret.length() > 1)
                secret = secret.substring(1,secret.length()-1);
            if ((Secret.decrypt(secret)) != null || SecretBytes.isSecretBytes(secret)) {
                xml = StringUtils.replace(xml, secret, SECRET_MARKER);
            }
        }

        return xml;
    }


}