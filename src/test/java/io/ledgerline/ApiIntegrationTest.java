package io.ledgerline;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class ApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void adminEndpointsNeedToken() throws Exception {
        mvc.perform(post("/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiNeedsValidKey() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts").header("X-Api-Key", "lk_nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedTenantIsLockedOut() throws Exception {
        JsonNode tenant = createTenant("acme");
        String key = tenant.get("apiKey").asText();
        mvc.perform(post("/admin/tenants/" + tenant.at("/tenant/id").asText() + "/suspend")
                .header("X-Admin-Token", "test-admin-token")).andExpect(status().isOk());

        mvc.perform(get("/api/accounts").header("X-Api-Key", key)).andExpect(status().isUnauthorized());
    }

    @Test
    void postEntryAndReadBalance() throws Exception {
        String key = createTenant("acme").get("apiKey").asText();
        String cash = openAccount(key, "1000", "ASSET");
        String revenue = openAccount(key, "4000", "INCOME");

        mvc.perform(post("/api/entries").header("X-Api-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference":"INV-7","description":"Invoice 7","currency":"USD","lines":[
                                  {"accountId":"%s","direction":"DEBIT","amountMinor":2500},
                                  {"accountId":"%s","direction":"CREDIT","amountMinor":2500}]}
                                """.formatted(cash, revenue)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines", hasSize(2)));
        projectAll();

        mvc.perform(get("/api/accounts/" + cash + "/balance").header("X-Api-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(2500));
        mvc.perform(get("/api/events").header("X-Api-Key", key))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].type").value("ENTRY_POSTED"));
    }

    @Test
    void unbalancedEntryIs422WithCode() throws Exception {
        String key = createTenant("acme").get("apiKey").asText();
        String cash = openAccount(key, "1000", "ASSET");
        String revenue = openAccount(key, "4000", "INCOME");

        mvc.perform(post("/api/entries").header("X-Api-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"bad","currency":"USD","lines":[
                                  {"accountId":"%s","direction":"DEBIT","amountMinor":10},
                                  {"accountId":"%s","direction":"CREDIT","amountMinor":9}]}
                                """.formatted(cash, revenue)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unbalanced"));
    }

    @Test
    void invalidRequestIs400() throws Exception {
        String key = createTenant("acme").get("apiKey").asText();

        mvc.perform(post("/api/accounts").header("X-Api-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"x\",\"type\":\"ASSET\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherTenantsAccountIs404() throws Exception {
        String acme = createTenant("acme").get("apiKey").asText();
        String globex = createTenant("globex").get("apiKey").asText();
        String cash = openAccount(acme, "1000", "ASSET");

        mvc.perform(get("/api/accounts/" + cash).header("X-Api-Key", globex)).andExpect(status().isNotFound());
    }

    private JsonNode createTenant(String slug) throws Exception {
        String body = mvc.perform(post("/admin/tenants").header("X-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + slug + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String openAccount(String key, String code, String type) throws Exception {
        String body = mvc.perform(post("/api/accounts").header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"type\":\"" + type
                                + "\",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }
}
