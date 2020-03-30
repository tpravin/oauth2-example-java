package com.deere.isg.examples;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import kong.unirest.Unirest;
import org.apache.http.client.utils.URIBuilder;
import org.json.JSONObject;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.template.mustache.MustacheTemplateEngine;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static com.google.common.collect.ImmutableMap.of;

public class Application {
    public void start() {
        Spark.port(9090);
        Spark.staticFileLocation("assets");
        Spark.get("/", (request, response) -> index(request, response));
        Spark.post("/", (request, response) -> startOIDC(request, response));
        Spark.get("/callback", this::processCallback);
        Spark.get("/refresh-access-token", this::refreshAccessToken);
        Spark.post("/call-api", this::callTheApi);
    }

    private MustacheTemplateEngine stache = new MustacheTemplateEngine();

    private Settings settings = new Settings();

    static class Settings {
//        public String clientId = "deereqa-Klry9FXtbruHIjRk63qgzuthjTQHuTgZEFM9RouO";
//        public String clientSecret = "713acdea07a552612d897833539e6037439cf033e189a6a1c94adbd743114abc";
//        public String wellKnown = "https://signin-qual.johndeere.com/oauth2/ausf4otb3gYxB9NOP0h7/.well-known/oauth-authorization-server";
//        public String callbackUrl = "http://localhost:9090/callback";

//        public String clientId = "johndeere-7d5qS7Wfxhq1emRPPHCQag85";
//        public String clientSecret = "066fb448220c11feca195675d85f61e244a0252f9a42086f0c1a2cb7e31b7f62";
//        public String wellKnown = "https://signin.johndeere.com/oauth2/aus78tnlaysMraFhC1t7/.well-known/oauth-authorization-server";
//        public String callbackUrl = "http://localhost:8080/login";

//        public String clientId = "deereqa-dNIsaluuh54mHUNbIXrrgid1MjTdTAQxLzc2I0CL";
//        public String clientSecret = "1e04fc627bce3c053a92aa14ce4ed6af6174b149302621907ac650c3ce2f69f9";
//        public String wellKnown = "https://signin-qual.johndeere.com/oauth2/ausf4otb3gYxB9NOP0h7/.well-known/oauth-authorization-server";
//        public String callbackUrl = "http://localhost:9090/callback";
//        public String callbackUrl = "https://devadminqa.deere.com/api/devconsole/oauth/redirect";

        public String clientId = "johndeere-lio7F93QNFfzBtkNAR8aFyDE1b53bDprAQoikH0p";
        public String clientSecret = "db3b7f696b0aaf10e9c44dd99eea541e6d22aeaf70297d4464b1275a77256109";
        public String wellKnown = "https://signin.johndeere.com/oauth2/aus78tnlaysMraFhC1t7/.well-known/oauth-authorization-server";
        public String callbackUrl = "http://localhost:9090/callback";
//        public String callbackUrl = "https://devadminqa.deere.com/api/devconsole/oauth/redirect";


        public String scopes = "ag1 ag2 ag3 eq1 eq2 org1 org2 files offline_access";
        public String state = UUID.randomUUID().toString();
        public String idToken;
        public String accessToken;
        public String refreshToken;
        public String apiResponse;
        public Long exp;

        public void populate(Request request) {
            this.clientId = request.queryParams("clientId");
            this.clientSecret = request.queryParams("clientSecret");
            this.wellKnown = request.queryParams("wellKnown");
            this.callbackUrl = request.queryParams("callbackUrl");
            this.scopes = request.queryParams("scopes");
            this.state = request.queryParams("state");
        }

        public String getAccessTokenDetails() {
            if (Strings.isNullOrEmpty(accessToken)) {
                return null;
            }
            String body = accessToken.split("\\.")[1];
            return new JSONObject(new String(Base64.getDecoder().decode(body))).toString(3);
        }

        public String getExpiration(){
            if(exp == null){
                return null;
            }
            return LocalDateTime.now().plusSeconds(exp).toString();
        }

        public String getBasicAuthHeader() {
            String header = String.format("%s:%s", clientId, clientSecret);
            return Base64.getEncoder().encodeToString(header.getBytes());
        }

        public void updateTokenInfo(JSONObject obj) {
//            idToken = obj.getString("id_token");
            accessToken = obj.getString("access_token");
            refreshToken = obj.getString("refresh_token");
            exp = obj.getLong("expires_in");
        }

    }

    public Object index(Request request, Response response) {
        return stache.render(new ModelAndView(settings, "main.mustache"));
    }

    private Object startOIDC(Request request, Response response) {
        settings.populate(request);
        String redirect = getRedirectUrl();
        response.redirect(redirect);
        return null;
    }

    private Object processCallback(Request request, Response response) {
        if (request.queryParams("error") != null) {
            return renderError(request.queryParams("error_description"));
        }

        try {
            String code = request.queryParams("code");
            JSONObject obj = Unirest.post(getLocationFromMeta("token_endpoint"))
                    .header("authorization", "Basic " + settings.getBasicAuthHeader())
                    .accept("application/json")
                    .field("grant_type", "authorization_code")
                    .field("redirect_uri", settings.callbackUrl)
                    .field("code", code)
                    .field("scope", settings.scopes)
                    .contentType("application/x-www-form-urlencoded")
                    .asJson()
                    .ifFailure(r -> {
                        throw new RequestException(r);
                    })
                    .getBody()
                    .getObject();

            settings.updateTokenInfo(obj);
        } catch (Exception e) {
            return renderError(Throwables.getStackTraceAsString(e));
        }

        return index(request, response);
    }

    private Object renderError(String errorMessage) {
        return stache.render(
                new ModelAndView(of("error", errorMessage), "error.mustache")
        );
    }


    private String getRedirectUrl() {
        try {
            String authEndpoint = getLocationFromMeta("authorization_endpoint");
            return new URIBuilder(URI.create(authEndpoint))
                    .addParameter("client_id", settings.clientId)
                    .addParameter("response_type", "code")
                    .addParameter("scope", settings.scopes)
                    .addParameter("redirect_uri", settings.callbackUrl)
                    .addParameter("state", settings.state)
                    .build()
                    .toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String getLocationFromMeta(String key) {
        return Unirest.get(settings.wellKnown)
                .asJson()
                .getBody()
                .getObject()
                .getString(key);
    }

    private Object refreshAccessToken(Request request, Response response) {
        try {
            JSONObject refresh = Unirest.post(getLocationFromMeta("token_endpoint"))
                    .header("authorization", "Basic " + settings.getBasicAuthHeader())
                    .accept("application/json")
                    .field("grant_type", "refresh_token")
                    .field("redirect_uri", settings.callbackUrl)
                    .field("refresh_token", settings.refreshToken)
                    .field("scope", settings.scopes)
                    .contentType("application/x-www-form-urlencoded")
                    .asJson()
                    .ifFailure(r -> {
                        throw new RequestException(r);
                    })
                    .getBody()
                    .getObject();
            settings.updateTokenInfo(refresh);
        } catch (Exception e) {
            return renderError(Throwables.getStackTraceAsString(e));
        }
        return index(request, response);
    }

    private Object callTheApi(Request request, Response response) {
        String path = request.queryParams("url");

        try {
        JSONObject apiResponse = Unirest.get(path)
                .header("authorization", "Bearer " + settings.accessToken)
                .accept("application/vnd.deere.axiom.v3+json")
                .asJson()
                .ifFailure(r -> {
                    throw new RequestException(r);
                })
                .getBody()
                .getObject();
        settings.apiResponse = apiResponse.toString(3);
        } catch (Exception e) {
            return renderError(Throwables.getStackTraceAsString(e));
        }
        return index(request, response);
    }
}
