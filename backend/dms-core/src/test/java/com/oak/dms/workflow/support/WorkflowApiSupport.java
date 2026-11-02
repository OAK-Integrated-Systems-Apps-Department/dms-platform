package com.oak.dms.workflow.support;

import com.oak.dms.notification.dispatch.NotificationDispatcher;
import com.oak.dms.notification.support.NotificationMailSupport;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * HTTP helpers shared by the workflow ITs: real MockMvc + Spring Security
 * with a real Jwt principal per user, JSON in and out, and a captured
 * {@link JavaMailSender} for the after-commit notification mails.
 */
public abstract class WorkflowApiSupport extends NotificationMailSupport {

    @Autowired
    protected WebApplicationContext context;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected WorkflowFixtures fx;
    @Autowired
    protected NotificationDispatcher notificationDispatcher;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    protected JsonNode call(MockHttpServletRequestBuilder request, JwtRequestPostProcessor as, Object body,
                            int expectedStatus) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request.with(as)).andReturn();
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as(request + " -> " + content)
                .isEqualTo(expectedStatus);
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    protected JsonNode get200(String path, User as) throws Exception {
        return call(get(path), fx.jwtFor(as), null, 200);
    }

    /** A raw GET that resolves async streaming (StreamingResponseBody), for binary/content endpoints. */
    protected MockHttpServletResponse getRaw(String path, JwtRequestPostProcessor as) throws Exception {
        MvcResult result = mvc.perform(get(path).with(as)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse();
    }

    protected JsonNode postAs(String path, User as, Object body, int expectedStatus) throws Exception {
        return call(post(path), fx.jwtFor(as), body, expectedStatus);
    }

    protected JsonNode adminPost(String path, User admin, Object body, int expectedStatus) throws Exception {
        return call(post(path), fx.tenantAdminJwtFor(admin), body, expectedStatus);
    }

    protected JsonNode adminPut(String path, User admin, Object body, int expectedStatus) throws Exception {
        return call(put(path), fx.tenantAdminJwtFor(admin), body, expectedStatus);
    }

    // ---------------------------------------------------------- template set-up

    /** Creates, configures and activates a template through the API as a tenant admin; returns its id. */
    protected UUID activeTemplate(User admin, String code, UUID documentTypeId, String onDocumentChange,
                                  List<Map<String, Object>> stages) throws Exception {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("name", code + " approval");
        create.put("code", code);
        create.put("documentTypeId", documentTypeId);
        create.put("onDocumentChange", onDocumentChange);
        UUID templateId = id(adminPost("/api/v1/workflow/templates", admin, create, 201), "templateId");
        for (Map<String, Object> stage : stages) {
            adminPost("/api/v1/workflow/templates/" + templateId + "/stages", admin, stage, 201);
        }
        adminPost("/api/v1/workflow/templates/" + templateId + "/activate", admin, null, 200);
        // Since V118 the API forces requires_signature on every stage, and a plain /approve is refused
        // (409 WORKFLOW_SIGNATURE_REQUIRED). These ITs exercise routing/concurrency/access with plain
        // approvals, so opt the stages out directly; signing itself is covered by the signature tests.
        fx.jdbc().update("update workflow_stages set requires_signature = false where template_id = ?", templateId);
        return templateId;
    }

    @SafeVarargs
    protected static Map<String, Object> stageDef(String name, String completion, String rejection, Integer slaHours,
                                                  Map<String, Object>... participants) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("completionRule", completion);
        s.put("rejectionRule", rejection);
        s.put("slaHours", slaHours);
        s.put("participants", List.of(participants));
        return s;
    }

    protected static Map<String, Object> entry(String type, UUID id) {
        return Map.of("assigneeType", type, "assigneeId", id);
    }

    // ---------------------------------------------------------- workflow calls

    protected JsonNode submit(User as, UUID documentId, int expectedStatus) throws Exception {
        return postAs("/api/v1/workflow/instances", as, Map.of("documentId", documentId), expectedStatus);
    }

    /** Submit naming which active template to use - required once a document type has more than one (V121). */
    protected JsonNode submit(User as, UUID documentId, UUID templateId, int expectedStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", documentId);
        body.put("templateId", templateId);
        return postAs("/api/v1/workflow/instances", as, body, expectedStatus);
    }

    /** Submit with supporting documents attached in the same transaction (each entry a SupportingDocumentInput map). */
    protected JsonNode submit(User as, UUID documentId, List<? extends Map<String, Object>> supportingDocuments,
                              int expectedStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", documentId);
        body.put("supportingDocuments", supportingDocuments);
        return postAs("/api/v1/workflow/instances", as, body, expectedStatus);
    }

    protected JsonNode approve(User as, UUID taskId, int expectedStatus) throws Exception {
        return postAs("/api/v1/workflow/tasks/" + taskId + "/approve", as, Map.of(), expectedStatus);
    }

    protected JsonNode reject(User as, UUID taskId, String reason, int expectedStatus) throws Exception {
        return postAs("/api/v1/workflow/tasks/" + taskId + "/reject", as,
                reason == null ? Map.of() : Map.of("reason", reason), expectedStatus);
    }

    protected JsonNode delegate(User as, UUID taskId, User to, int expectedStatus) throws Exception {
        return postAs("/api/v1/workflow/tasks/" + taskId + "/delegate", as, Map.of("delegateUserId", to.getUserId()),
                expectedStatus);
    }

    /** As a tenant admin (who holds wa.reassign-workflow-task implicitly). */
    protected JsonNode reassign(User admin, UUID taskId, User to, String reason, int expectedStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", to.getUserId());
        body.put("reason", reason);
        return adminPost("/api/v1/workflow/tasks/" + taskId + "/reassign", admin, body, expectedStatus);
    }

    /** The caller's My Tasks rows for a view (all, assigned, available, claimed, waiting). */
    protected JsonNode myTasks(User as, String view) throws Exception {
        return get200("/api/v1/workflow/tasks/my?size=100&view=" + view, as).get("content");
    }

    protected JsonNode instance(UUID instanceId, User as) throws Exception {
        return get200("/api/v1/workflow/instances/" + instanceId, as);
    }

    protected List<UUID> myTaskIds(User as) throws Exception {
        List<UUID> ids = new ArrayList<>();
        get200("/api/v1/workflow/tasks/my?size=100", as).get("content")
                .forEach(t -> ids.add(UUID.fromString(t.get("taskId").asString())));
        return ids;
    }

    /** The task of stage {@code stageOrder} whose assignee name is {@code assigneeName}. */
    protected static UUID taskId(JsonNode instance, int stageOrder, String assigneeName) {
        for (JsonNode stage : instance.get("stages")) {
            if (stage.get("stageOrder").asInt() == stageOrder) {
                for (JsonNode task : stage.get("tasks")) {
                    if (assigneeName.equals(task.get("assigneeName").asString())) {
                        return UUID.fromString(task.get("taskId").asString());
                    }
                }
            }
        }
        throw new AssertionError("No task for " + assigneeName + " in stage " + stageOrder + ": " + instance);
    }

    protected static JsonNode stage(JsonNode instance, int stageOrder) {
        for (JsonNode stage : instance.get("stages")) {
            if (stage.get("stageOrder").asInt() == stageOrder) {
                return stage;
            }
        }
        throw new AssertionError("No stage " + stageOrder);
    }

    protected static JsonNode task(JsonNode instance, UUID taskId) {
        for (JsonNode stage : instance.get("stages")) {
            for (JsonNode task : stage.get("tasks")) {
                if (taskId.toString().equals(task.get("taskId").asString())) {
                    return task;
                }
            }
        }
        throw new AssertionError("No task " + taskId);
    }

    protected static UUID id(JsonNode node, String field) {
        return UUID.fromString(node.get(field).asString());
    }

    // ---------------------------------------------------------------- mail

    /**
     * A captured email from GreenMail, exposing the same getters the old
     * {@code SimpleMailMessage}-based assertions used (3.2, N8). Email now
     * goes out via the notification dispatcher as HTML + text; {@link #getText()}
     * returns the whole body (text + html parts joined by GreenMail).
     */
    public record CapturedMail(String subject, String text, String[] to) {
        public String getSubject() {
            return subject;
        }

        public String getText() {
            return text;
        }

        public String[] getTo() {
            return to;
        }
    }

    protected List<CapturedMail> sentMails() {
        List<CapturedMail> mails = new ArrayList<>();
        for (var m : allMail()) {
            mails.add(new CapturedMail(subjectOf(m), bodyOf(m), recipients(m).toArray(new String[0])));
        }
        return mails;
    }

    /**
     * Notifications are written in the triggering transaction; email is sent by
     * the dispatcher, which is off in tests - so drive it each poll and wait for
     * the matching mail (up to 5 s).
     */
    protected CapturedMail awaitMail(Predicate<CapturedMail> match) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            notificationDispatcher.runOnce();
            for (CapturedMail m : sentMails()) {
                if (match.test(m)) {
                    return m;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No matching mail; sent: "
                + sentMails().stream().map(CapturedMail::getSubject).toList());
    }

    protected static Predicate<CapturedMail> to(User user) {
        return m -> m.getTo() != null && List.of(m.getTo()).contains(user.getEmail());
    }

    protected static Predicate<CapturedMail> subject(String part) {
        return m -> m.getSubject() != null && m.getSubject().contains(part);
    }
}
