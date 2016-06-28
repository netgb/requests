package net.dongliu.requests;

import net.dongliu.commons.collection.Pair;
import net.dongliu.commons.io.Closeables;
import net.dongliu.requests.body.RequestBody;
import net.dongliu.requests.exception.RequestsException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

import static java.util.stream.Collectors.*;
import static net.dongliu.requests.HttpHeaders.*;

/**
 * Execute http request with url connection
 *
 * @author Liu Dong
 */
public class URLConnectionExecutor implements HttpExecutor {
    @Override
    public RawResponse proceed(HttpRequest request) {
        RawResponse response = doRequest(request);

        if (!request.isFollowRedirect() || !Utils.isRedirect(response.getStatusCode())) {
            return response;
        }

        // handle redirect
        response.discardBody();
        int redirectCount = 0;
        URL redirectUrl = request.getUrl();
        while (redirectCount++ < 5) {
            String location = response.getFirstHeader(NAME_LOCATION);
            if (location == null) {
                throw new RequestsException("Redirect location not found");
            }
            try {
                redirectUrl = new URL(redirectUrl, location);
            } catch (MalformedURLException e) {
                throw new RequestsException("Get redirect url error", e);
            }
            response = request.getSession().get(redirectUrl.toExternalForm())
                    .proxy(request.getProxy()).followRedirect(false).send();
            if (!Utils.isRedirect(response.getStatusCode())) {
                return response;
            }
            response.discardBody();
        }
        throw new RequestsException("Too many redirect");
    }


    private RawResponse doRequest(HttpRequest request) {
        Charset charset = request.getCharset();
        URL url = request.getUrl();
        Proxy proxy = request.getProxy();
        RequestBody body = request.getBody();
        Session session = request.getSession();

        String protocol = url.getProtocol();
        String host = url.getHost(); // only domain, do not have port
        // effective path for cookie
        String effectivePath = CookieUtils.effectivePath(url.getPath());

        HttpURLConnection conn;
        try {
            if (proxy != null) {
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
        } catch (MalformedURLException e) {
            throw new RequestsException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // deal with https
        if (conn instanceof HttpsURLConnection) {
            SSLSocketFactory ssf = null;
            if (!request.isVerify()) {
                ssf = SSLSocketFactories.getTrustAllSSLSocketFactory();
            } else if (!request.getCerts().isEmpty()) {
                ssf = SSLSocketFactories.getCustomSSLSocketFactory(request.getCerts());
            }
            if (ssf != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(ssf);
            }
        }

        try {
            conn.setRequestMethod(request.getMethod());
        } catch (ProtocolException e) {
            throw new RequestsException(e);
        }
        conn.setReadTimeout(request.getSocksTimeout());
        conn.setConnectTimeout(request.getConnectTimeout());
        // Url connection did not deal with cookie when handle redirect. Disable it and handle it manually
        conn.setInstanceFollowRedirects(false);
        if (body != null) {
            conn.setDoOutput(true);
            String contentType = body.getContentType();
            if (contentType != null) {
                if (body.isIncludeCharset()) {
                    contentType += "; charset=" + request.getCharset().name().toLowerCase();
                }
                conn.setRequestProperty(NAME_CONTENT_TYPE, contentType);
            }
        }

        // headers
        if (!request.getUserAgent().isEmpty()) {
            conn.setRequestProperty(NAME_USER_AGENT, request.getUserAgent());
        }
        if (request.isCompress()) {
            conn.setRequestProperty(NAME_ACCEPT_ENCODING, "gzip, deflate");
        }

        if (request.getBasicAuth() != null) {
            conn.setRequestProperty(NAME_AUTHORIZATION, request.getBasicAuth().encode());
        }
        // http proxy authentication
        if (proxy != null && proxy instanceof Proxies.AuthenticationHttpProxy) {
            BasicAuth basicAuth = ((Proxies.AuthenticationHttpProxy) proxy).getBasicAuth();
            conn.setRequestProperty(NAME_PROXY_AUTHORIZATION, basicAuth.encode());
        }

        // set cookies
        if (!request.getCookies().isEmpty() || !session.getCookies().isEmpty()) {
            Stream<? extends Map.Entry<String, ?>> stream1 = request.getCookies().stream();
            Stream<? extends Map.Entry<String, ?>> stream2 = session.matchedCookies(protocol, host, effectivePath)
                    .map(c -> Pair.of(c.getName(), c.getValue()));
            String cookieStr = Stream.concat(stream1, stream2).map(c -> c.getKey() + "=" + c.getValue())
                    .collect(joining("; "));
            if (!cookieStr.isEmpty()) {
                conn.setRequestProperty(NAME_COOKIE, cookieStr);
            }
        }

        // set user custom headers
        for (Map.Entry<String, ?> header : request.getHeaders()) {
            conn.setRequestProperty(header.getKey(), String.valueOf(header.getValue()));
        }

        // disable keep alive
        if (!request.isKeepAlive()) {
            conn.setRequestProperty("Connection", "close");
        }

        try {
            conn.connect();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            // send body
            if (body != null) {
                sendBody(body, conn, charset);
            }
            return getResponse(conn, session, request.getMethod(), host, effectivePath);
        } catch (IOException e) {
            conn.disconnect();
            throw new UncheckedIOException(e);
        } catch (Throwable e) {
            conn.disconnect();
            throw e;
        }
    }

    /**
     * Wrap response, deal with headers and cookies
     *
     * @throws IOException
     */
    private RawResponse getResponse(HttpURLConnection conn, Session session,
                                    String method, String host, String path) throws IOException {
        // read result
        int status = conn.getResponseCode();

        // headers and cookies
        List<Map.Entry<String, String>> headerList = new ArrayList<>();
        Set<Cookie> cookies = new HashSet<>();
        int index = 0;
        while (true) {
            String key = conn.getHeaderFieldKey(index);
            String value = conn.getHeaderField(index);
            if (value == null) {
                break;
            }
            index++;
            //status line
            if (key == null) {
                continue;
            }
            headerList.add(Pair.of(key, value));
            if (key.equalsIgnoreCase(NAME_SET_COOKIE)) {
                cookies.add(CookieUtils.parseCookieHeader(host, path, value));
            }
        }
        ResponseHeaders headers = new ResponseHeaders(headerList);

        InputStream input;
        try {
            input = conn.getInputStream();
        } catch (IOException e) {
            input = conn.getErrorStream();
        }
        if (input != null) {
            // deal with [compressed] input
            input = wrapCompressBody(status, method, headers, input);
        } else {
            input = new ByteArrayInputStream(new byte[0]);
        }

        // update session
        session.updateCookie(cookies);
        return new RawResponse(status, headers, cookies, input, conn);
    }

    /**
     * Wrap response input stream if it is compressed, return input its self if not use compress
     */
    private InputStream wrapCompressBody(int status, String method, ResponseHeaders headers,
                                         InputStream input) {
        // if has no body, some server still set content-encoding header,
        // GZIPInputStream wrap empty input stream will cause exception. we should check this
        if (method.equals("HEAD") || (status >= 100 && status < 200) || status == 304 || status == 204) {
            return input;
        }

        String contentEncoding = headers.getFirstHeader(NAME_CONTENT_ENCODING);
        if (contentEncoding == null) {
            return input;
        }
        //TODO: we should remove the content-encoding header here?
        switch (contentEncoding) {
            case "gzip":
                try {
                    return new GZIPInputStream(input);
                } catch (IOException e) {
                    Closeables.closeQuietly(input);
                    throw new UncheckedIOException(e);
                }
            case "deflate":
                return new DeflaterInputStream(input);
            case "identity":
            case "compress": //historic; deprecated in most applications and replaced by gzip or deflate
            default:
                return input;
        }
    }

    private void sendBody(RequestBody body, HttpURLConnection conn, Charset requestCharset) {
        try (OutputStream os = conn.getOutputStream()) {
            body.writeBody(os, requestCharset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
