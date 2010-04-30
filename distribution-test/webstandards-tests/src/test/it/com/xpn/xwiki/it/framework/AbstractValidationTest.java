/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xpn.xwiki.it.framework;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.xwiki.model.EntityType;
import org.xwiki.model.internal.reference.DefaultStringEntityReferenceResolver;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.validator.Validator;

import com.xpn.xwiki.plugin.packaging.Package;

public class AbstractValidationTest extends TestCase
{
    protected HttpClient client;

    protected Target target;

    private static final EntityReferenceResolver<String> RESOLVER = new DefaultStringEntityReferenceResolver();

    public AbstractValidationTest(String name, Target target, HttpClient client)
    {
        super(name);

        this.target = target;
        this.client = client;
    }

    protected GetMethod createGetMethod() throws UnsupportedEncodingException
    {
        GetMethod getMethod = null;

        if (this.target instanceof DocumentReferenceTarget) {
            DocumentReferenceTarget documentReferenceTarget = (DocumentReferenceTarget) this.target;
            getMethod =
                new GetMethod("http://127.0.0.1:8080/xwiki/bin/view/"
                    + URLEncoder.encode(documentReferenceTarget.getDocumentReference().getLastSpaceReference()
                        .getName(), "UTF-8") + "/"
                    + URLEncoder.encode(documentReferenceTarget.getDocumentReference().getName(), "UTF-8"));
        } else if (this.target instanceof URLPathTarget) {
            String urlPath = ((URLPathTarget) this.target).getUrlPath();

            if (urlPath.startsWith("http://")) {
                getMethod = new GetMethod(urlPath);
            } else {
                getMethod = new GetMethod("http://127.0.0.1:8080" + urlPath);
            }
        }

        return getMethod;
    }

    protected byte[] getResponseBody() throws IOException
    {
        GetMethod method = createGetMethod();

        method.setDoAuthentication(true);
        method.setFollowRedirects(true);
        method.addRequestHeader("Authorization", "Basic " + new String(Base64.encodeBase64("Admin:admin".getBytes())));

        // Execute the method.
        try {
            int statusCode = this.client.executeMethod(method);

            assertEquals("Method failed: " + method.getStatusLine(), HttpStatus.SC_OK, statusCode);

            // Read the response body.
            return method.getResponseBody();
        } finally {
            method.releaseConnection();
        }
    }

    public static Test suite(Class< ? extends AbstractValidationTest> validationTest, Validator validator)
        throws Exception
    {
        TestSuite suite = new TestSuite();

        HttpClient adminClient = new HttpClient();
        Credentials defaultcreds = new UsernamePasswordCredentials("Admin", "admin");
        adminClient.getState().setCredentials(AuthScope.ANY, defaultcreds);

        addURLsForAdmin(validationTest, validator, suite, adminClient);
        addXarFiles(validationTest, validator, suite, adminClient);

        HttpClient guestClient = new HttpClient();

        addURLsForGuest(validationTest, validator, suite, guestClient);

        return suite;
    }

    public static void addURLsForAdmin(Class< ? extends AbstractValidationTest> validationTest, Validator validator,
        TestSuite suite, HttpClient client) throws Exception
    {
        addURLs("urlsToTestAsAdmin", validationTest, validator, suite, client);
    }

    public static void addURLsForGuest(Class< ? extends AbstractValidationTest> validationTest, Validator validator,
        TestSuite suite, HttpClient client) throws Exception
    {
        addURLs("urlsToTestAsGuest", validationTest, validator, suite, client);
    }

    public static void addURLs(String property, Class< ? extends AbstractValidationTest> validationTest,
        Validator validator, TestSuite suite, HttpClient client) throws Exception
    {
        String urlsToTest = System.getProperty(property);

        for (String url : urlsToTest.split("\\s")) {
            if (StringUtils.isNotEmpty(url)) {
                suite.addTest(validationTest.getConstructor(Target.class, HttpClient.class, Validator.class)
                    .newInstance(new URLPathTarget(url), client, validator));
            }
        }
    }

    public static void addXarFiles(Class< ? extends AbstractValidationTest> validationTest, Validator validator,
        TestSuite suite, HttpClient client) throws Exception
    {
        String path = System.getProperty("localRepository") + "/" + System.getProperty("pathToXWikiXar");
        String patternFilter = System.getProperty("documentsToTest");

        for (DocumentReference documentReference : readXarContents(path, patternFilter)) {
            suite.addTest(validationTest.getConstructor(Target.class, HttpClient.class, Validator.class).newInstance(
                new DocumentReferenceTarget(documentReference), client, validator));
        }
    }

    public static List<DocumentReference> readXarContents(String fileName, String patternFilter) throws Exception
    {
        FileInputStream fileIS = new FileInputStream(fileName);
        ZipInputStream zipIS = new ZipInputStream(fileIS);

        ZipEntry entry;
        Document tocDoc = null;
        while ((entry = zipIS.getNextEntry()) != null) {
            if (entry.getName().compareTo(Package.DefaultPackageFileName) == 0) {
                SAXReader reader = new SAXReader();
                tocDoc = reader.read(zipIS);
                break;
            }
        }

        if (tocDoc == null) {
            return Collections.emptyList();
        }

        List<DocumentReference> result = new ArrayList<DocumentReference>();

        Pattern pattern = patternFilter == null ? null : Pattern.compile(patternFilter);

        Element filesElement = tocDoc.getRootElement().element("files");
        List<Element> fileElementList = filesElement.elements("file");
        for (Element el : fileElementList) {
            String docFullName = el.getStringValue();

            if (pattern == null || pattern.matcher(docFullName).matches()) {
                result.add(new DocumentReference(RESOLVER.resolve("xwiki:" + docFullName, EntityType.DOCUMENT)));
            }
        }

        return result;
    }
}
