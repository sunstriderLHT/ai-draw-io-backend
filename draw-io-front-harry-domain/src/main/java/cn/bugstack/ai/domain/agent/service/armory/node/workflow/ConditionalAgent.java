package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Collections;
import java.util.List;

public class ConditionalAgent extends BaseAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String stateKey;
    private final String jsonField;
    private final String expectedValue;

    public ConditionalAgent(
            String name,
            String description,
            List<? extends BaseAgent> subAgents,
            String stateKey,
            String jsonField,
            String expectedValue
    ) {
        super(name, description, subAgents, Collections.emptyList(), Collections.emptyList());
        this.stateKey = stateKey;
        this.jsonField = jsonField;
        this.expectedValue = expectedValue;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        if (!conditionMatches(invocationContext)) {
            return Flowable.empty();
        }
        return Flowable.fromIterable(subAgents())
                .concatMap(agent -> agent.runAsync(invocationContext));
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        if (!conditionMatches(invocationContext)) {
            return Flowable.empty();
        }
        return Flowable.fromIterable(subAgents())
                .concatMap(agent -> agent.runLive(invocationContext));
    }

    private boolean conditionMatches(InvocationContext invocationContext) {
        Object stateValue = invocationContext.session().state().get(stateKey);
        if (!(stateValue instanceof String) || ((String) stateValue).trim().isEmpty()) {
            return false;
        }

        try {
            JsonNode stateJson = OBJECT_MAPPER.readTree((String) stateValue);
            if (stateJson == null) {
                return false;
            }
            JsonNode fieldValue = stateJson.get(jsonField);
            return fieldValue != null && expectedValue.equals(fieldValue.asText());
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }
}
