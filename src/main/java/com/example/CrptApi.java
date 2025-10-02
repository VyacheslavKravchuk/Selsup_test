package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Data;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CrptApi {

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final Semaphore semaphore;
    private final HttpClient httpClient;
    private final String apiUrl;
    private final Gson gson;
    private final String authToken = "clientToken";
    private final long tokenExpirationTime;
    private final Object tokenLock = new Object();

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, "https://ismp.crpt.ru");
    }

    public CrptApi(TimeUnit timeUnit, int requestLimit, String apiUrl) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должен быть положительным числом");
        }
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.semaphore = new Semaphore(requestLimit, true);
        this.httpClient = HttpClient.newHttpClient();
        this.apiUrl = apiUrl;
        this.gson = new Gson();
        this.tokenExpirationTime = 0;
    }

    public static String stringToBase64(String data) {
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(dataBytes);
    }

    private AuthKeyResponse getAuthKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/v3/auth/cert/key"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
            String uuid = jsonResponse.get("uuid").getAsString();
            String data = jsonResponse.get("data").getAsString();
            AuthKeyResponse authKeyResponse = new AuthKeyResponse();
            authKeyResponse.setUuid(uuid);
            authKeyResponse.setData(data);
            return authKeyResponse;
        } else {
            throw new IOException("Ошибка при получении данных: " + response.statusCode() + ", body: " + response.body());
        }
    }

    //В связи с невозможностью подписания данных с помощью ЭП для получения токена,
    //мы будем использовать имитацию токена и ответа от сервера
    public void createDocument(Document document, String signature) throws IOException, InterruptedException {
        try {
            semaphore.acquire();
            try {
                String jsonPayload = convertToJson(document, signature);

                // Имитация ответа сервера
                String simulatedResponse = "< HTTP/1.1 200\n" +
                        "< Content-Type: application/json;charset=UTF-8\n" +
                        "< Authorization: Bearer " + authToken + "\n" +
                        jsonPayload;
                System.out.println("Имитация ответа сервера:\n" + simulatedResponse);

            } finally {
                timeUnit.sleep(1);
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Произошла ошибка: " + e.getMessage());
            throw e;
        }
    }

    @Data
    public static class Document {
        public Description description;
        public String doc_id;
        public String doc_status;
        public String doc_type;
        public boolean importRequest;
        public String owner_inn;
        public String participant_inn;
        public String producer_inn;
        public String production_date;
        public String production_type;
        public Product[] products;
        public String reg_date;
        public String reg_number;

        @Data
        static class Product {
            public String certificate_document;
            public String certificate_document_date;
            public String certificate_document_number;
            public String owner_inn;
            public String producer_inn;
            public String production_date;
            public String tnved_code;
            public String uit_code;
            public String uitu_code;
        }
    }

    @Data
    public static class Description {
        private String participantInn;
    }

    @Data
    private static class AuthKeyResponse {
        private String uuid;
        private String data;
    }

    private String convertToJson(Document document, String signature) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("document", gson.toJsonTree(document));
        jsonObject.addProperty("signature", signature);
        return gson.toJson(jsonObject);
    }

    public static void main(String[] args) throws InterruptedException, IOException {

        CrptApi api = new CrptApi(TimeUnit.SECONDS, 5);

        System.out.println(api.getAuthKey());

        Document document = getDocument();

        //удобочитаемый JSON для наглядности
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            String jsonString = mapper.writeValueAsString(document);
            System.out.println("JSON: \n" + jsonString);

        } catch (Exception e) {
            System.err.println("Ошибка сериализации JSON" + e.getMessage());
        }

        String signature = "подпись";

        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    api.createDocument(document, signature);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Ошибка в потоке: " + e.getMessage());
                } finally {
                    if (counter.incrementAndGet() == 10) {
                        System.out.println("Завершено.");
                    }
                }
            }).start();
            Thread.sleep(200);
        }
    }

    private static Document getDocument() {
        Description description = new Description();
        description.setParticipantInn("123456789012");

        Document.Product product = new Document.Product();
        product.setCertificate_document("certificate123");
        product.setCertificate_document_date("2023-10-26");
        product.setCertificate_document_number("cert12345");
        product.setOwner_inn("987654321098");
        product.setProducer_inn("555555555555");
        product.setProduction_date("2023-10-25");
        product.setTnved_code("8471300000");
        product.setUit_code("uit123");
        product.setUitu_code("uitu456");

        return getDocument(description, product);
    }

    private static Document getDocument(Description description, Document.Product product) {
        Document document = new Document();
        document.setDescription(description);
        document.setDoc_id("doc12345");
        document.setDoc_status("ACTIVE");
        document.setDoc_type("LP_INTRODUCE_GOODS");
        document.setImportRequest(true);
        document.setOwner_inn("111111111111");
        document.setParticipant_inn("222222222222");
        document.setProducer_inn("333333333333");
        document.setProduction_date("2023-10-24");
        document.setProduction_type("SERIAL");
        document.setProducts(new Document.Product[]{product});
        document.setReg_date("2023-10-23");
        document.setReg_number("reg98765");
        return document;
    }
}


