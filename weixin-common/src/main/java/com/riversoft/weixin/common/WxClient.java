package com.riversoft.weixin.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.riversoft.weixin.common.exception.WxError;
import com.riversoft.weixin.common.exception.WxRuntimeException;
import com.riversoft.weixin.common.util.JsonMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by exizhai on 9/26/2015.
 */
public class WxClient {

    private static Logger logger = LoggerFactory.getLogger(WxClient.class);

    protected CloseableHttpClient httpClient;
    private AccessToken accessToken;
    private String clientId;
    private String clientSecret;
    private String tokenUrl;

    public WxClient() {
        httpClient = HttpClients.createDefault();
    }

    public WxClient(String tokenUrl, String clientId, String clientSecret) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        httpClient = HttpClients.createDefault();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String get(String url) {
        return httpGet(appendAccessToken(url));
    }

    public String get(String url, boolean needToken) {
        return httpGet(needToken ? appendAccessToken(url) : url);
    }

    public byte[] getBinary(String url, boolean needToken) {
        return httpGetBinary(needToken ? appendAccessToken(url) : url);
    }

    private byte[] httpGetBinary(String url) {
        HttpGet httpGet = new HttpGet(url);

        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            }

            Header[] contentTypeHeader = response.getHeaders("Content-Type");
            if (contentTypeHeader != null && contentTypeHeader.length > 0) {
                if (ContentType.TEXT_PLAIN.getMimeType().equals(contentTypeHeader[0].getValue())) {
                    String responseContent = entity == null ? null : EntityUtils.toString(entity, Consts.UTF_8);
                    WxError wxError = WxError.fromJson(responseContent);
                    if (wxError.getErrorCode() != 0) {
                        throw new WxRuntimeException(wxError);
                    }
                }
            }
            InputStream inputStream = response.getEntity().getContent();
            return IOUtils.toByteArray(inputStream);
        } catch (IOException ex) {
            logger.error("http get: {} failed.", url, ex);
            throw new WxRuntimeException(999, ex.getMessage());
        }
    }


    private String httpGet(String url) {
        HttpGet httpGet = new HttpGet(url);

        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            }
            String responseContent = entity == null ? null : EntityUtils.toString(entity, Consts.UTF_8);

            WxError wxError = WxError.fromJson(responseContent);

            if (invalidToken(wxError.getErrorCode())) {
                refreshToken();
            }

            if (wxError.getErrorCode() != 0) {
                throw new WxRuntimeException(wxError);
            }
            return responseContent;
        } catch (IOException ex) {
            logger.error("http get: {} failed.", url, ex);
            throw new WxRuntimeException(999, ex.getMessage());
        }
    }

    private boolean invalidToken(int code) {
        return code == 42001 || code == 40001 || code == 40014;
    }

    public File download(String url) {
        return httpDownload(appendAccessToken(url));
    }

    public InputStream copyStream(String url, String post) {
        return httpCopyFromStream(appendAccessToken(url), post);
    }

    /**
     * 永久素材下载使用,奇葩的下载方式
     * @param url
     * @param post
     * @return
     */
    private InputStream httpCopyFromStream(String url, String post) {
        HttpPost httpPost = new HttpPost(url);

        if (post != null) {
            StringEntity entity = new StringEntity(post, Consts.UTF_8);
            httpPost.setEntity(entity);
        }

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            } else {
                InputStream inputStream = entity.getContent();
                byte[] binaryContent = IOUtils.toByteArray(inputStream);
                String content = new String(binaryContent, "UTF-8");
                if (content.contains("errcode")) {
                    WxError wxError = WxError.fromJson(content);
                    throw new WxRuntimeException(wxError);
                } else {
                    return new ByteArrayInputStream(binaryContent);
                }
            }
        } catch (IOException e) {
            logger.error("http download: {} failed.", url, e);
            throw new WxRuntimeException(999, e.getMessage());
        }
    }

    private File httpDownload(String url) {
        HttpGet httpGet = new HttpGet(url);

        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            } else {
                Header[] dispositionHeaders = response.getHeaders("Content-disposition");
                if (dispositionHeaders != null && dispositionHeaders.length > 0) {
                    String fileName = extractFileName(dispositionHeaders[0].getValue());
                    if (fileName == null || "".equals(fileName.trim())) {
                        logger.warn("Cannot get filename from Content-disposition");
                        fileName = UUID.randomUUID().toString();
                    }
                    InputStream inputStream = entity.getContent();
                    File tempFile = new File(FileUtils.getTempDirectory(), fileName);
                    FileUtils.copyInputStreamToFile(inputStream, tempFile);

                    return tempFile;
                } else {
                    String errors = entity == null ? null : EntityUtils.toString(entity, Consts.UTF_8);
                    logger.warn("download file : {} failed: {}", url, errors);
                    throw new WxRuntimeException(999, errors);
                }
            }
        } catch (IOException e) {
            logger.error("http download: {} failed.", url, e);
            throw new WxRuntimeException(999, e.getMessage());
        }
    }

    private String extractFileName(String headerValue) {
        String fileName = null;
        Pattern regex = Pattern.compile("(?<=filename=\").*?(?=\")");
        Matcher regexMatcher = regex.matcher(headerValue);
        if (regexMatcher.find()) {
            fileName = regexMatcher.group();
        }

        return fileName;
    }


    public String post(String url, String content) {
        return httpPost(appendAccessToken(url), content);
    }

    public String post(String url, InputStream inputStream, String fileName, Map<String, String> form) {
        File tempFile = new File(FileUtils.getTempDirectory(), fileName);

        try {
            FileUtils.copyInputStreamToFile(inputStream, tempFile);

            return httpPost(appendAccessToken(url), tempFile, form);
        } catch (IOException e) {
            logger.error("http post: {} failed", url, e);
            throw new WxRuntimeException(999, "Copy stream to file failed:" + e.getMessage());
        } finally {
            FileUtils.deleteQuietly(tempFile);
        }
    }

    public String post(String url, InputStream inputStream, String fileName) {
        return post(url, inputStream, fileName, null);
    }

    private String httpPost(String url, String content) {
        HttpPost httpPost = new HttpPost(url);

        if (content != null) {
            StringEntity entity = new StringEntity(content, Consts.UTF_8);
            httpPost.setEntity(entity);
        }

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            }
            String responseContent = entity == null ? null : EntityUtils.toString(entity, Consts.UTF_8);

            WxError wxError = WxError.fromJson(responseContent);

            if (invalidToken(wxError.getErrorCode())) {
                refreshToken();
            }

            if (wxError.getErrorCode() != 0) {
                throw new WxRuntimeException(wxError);
            }
            return responseContent;
        } catch (IOException ex) {
            logger.error("http post: {} failed", url, ex);
            throw new WxRuntimeException(999, ex.getMessage());
        }
    }

    private String httpPost(String url, File file) {
        return httpPost(url, file, null);
    }

    private String httpPost(String url, File file, Map<String, String> form) {
        HttpPost httpPost = new HttpPost(url);

        if (file != null) {
            MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
            multipartEntityBuilder.addBinaryBody("media", file).setMode(HttpMultipartMode.RFC6532);
            if(form != null && !form.isEmpty()) {
                for(String key: form.keySet()) {
                    multipartEntityBuilder.addTextBody(key, form.get(key));
                }
            }
            httpPost.setEntity(multipartEntityBuilder.build());
            httpPost.setHeader("Content-Type", ContentType.MULTIPART_FORM_DATA.toString());
        }

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            if (statusLine.getStatusCode() >= 300) {
                EntityUtils.consume(entity);
                throw new WxRuntimeException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            }
            return entity == null ? null : EntityUtils.toString(entity, Consts.UTF_8);
        } catch (IOException ex) {
            logger.error("http post: {} failed", url, ex);
            throw new WxRuntimeException(999, ex.getMessage());
        }
    }

    private String appendAccessToken(String url) {
        if (accessToken == null || accessToken.expired()) {
            refreshToken();
        }
        String token = accessToken.getAccessToken();
        logger.debug("access token: {}", token);
        return url + (url.indexOf('?') == -1 ? "?access_token=" + token : "&access_token=" + token);
    }

    public synchronized void refreshToken() {
        logger.debug("requesting a new access token.");
        String content = httpGet(String.format(tokenUrl, clientId, clientSecret));
        AccessToken accessToken = AccessToken.fromJson(content);
        logger.debug("requested a new access token: {}", accessToken.accessToken);
        this.accessToken = accessToken;
    }

    public AccessToken getAccessToken() {
        if (accessToken == null) {
            refreshToken();
        }
        return accessToken;
    }

    public static class AccessToken {

        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("expires_in")
        private long expiresIn;

        private long expiresTill;

        public static AccessToken fromJson(String json) {
            return JsonMapper.defaultMapper().fromJson(json, AccessToken.class);
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public long getExpiresIn() {
            return expiresIn;
        }

        public void setExpiresIn(long expiresIn) {
            this.expiresIn = expiresIn;
            this.expiresTill = System.currentTimeMillis() + (expiresIn * 1000) - 300000;
        }

        public long getExpiresTill() {
            return expiresTill;
        }

        public boolean expired() {
            return System.currentTimeMillis() > expiresTill;
        }
    }
}
