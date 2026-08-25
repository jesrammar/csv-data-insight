package com.asecon.enterpriseiq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BudgetSemanticDictionary {
    private static final String BASE_RESOURCE = "/semantic/budget-semantic-aliases.json";
    private static final String CLIENT_RESOURCE = "/semantic/budget-semantic-client-aliases.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DictionaryPayload BASE = loadBase();
    private static final Map<String, Map<String, ConceptAliases>> CLIENT_OVERRIDES = loadClientOverrides();

    private BudgetSemanticDictionary() {}

    public static Set<String> aliasesFor(String concept, String clientKey) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        ConceptAliases base = BASE.concepts().get(concept);
        if (base != null) {
            if (base.aliasesEs() != null) aliases.addAll(base.aliasesEs());
            if (base.aliasesEn() != null) aliases.addAll(base.aliasesEn());
        }
        if (clientKey != null) {
            Map<String, ConceptAliases> clientConcepts = CLIENT_OVERRIDES.get(clientKey);
            if (clientConcepts != null) {
                ConceptAliases override = clientConcepts.get(concept);
                if (override != null) {
                    if (override.aliasesEs() != null) aliases.addAll(override.aliasesEs());
                    if (override.aliasesEn() != null) aliases.addAll(override.aliasesEn());
                }
            }
        }
        return aliases;
    }

    public static Set<String> exclusionsFor(String concept, String clientKey) {
        LinkedHashSet<String> exclusions = new LinkedHashSet<>();
        ConceptAliases base = BASE.concepts().get(concept);
        if (base != null && base.exclusions() != null) exclusions.addAll(base.exclusions());
        if (clientKey != null) {
            Map<String, ConceptAliases> clientConcepts = CLIENT_OVERRIDES.get(clientKey);
            if (clientConcepts != null) {
                ConceptAliases override = clientConcepts.get(concept);
                if (override != null && override.exclusions() != null) exclusions.addAll(override.exclusions());
            }
        }
        return exclusions;
    }

    public static Set<String> supportedConcepts() {
        return BASE.concepts().keySet();
    }

    private static DictionaryPayload loadBase() {
        try (InputStream in = BudgetSemanticDictionary.class.getResourceAsStream(BASE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No se encuentra el diccionario semantico base " + BASE_RESOURCE);
            }
            DictionaryPayload payload = OBJECT_MAPPER.readValue(in, DictionaryPayload.class);
            return payload == null || payload.concepts() == null
                ? new DictionaryPayload(new LinkedHashMap<>())
                : payload;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar el diccionario semantico base.", ex);
        }
    }

    private static Map<String, Map<String, ConceptAliases>> loadClientOverrides() {
        try (InputStream in = BudgetSemanticDictionary.class.getResourceAsStream(CLIENT_RESOURCE)) {
            if (in == null) return Map.of();
            ClientOverridesPayload payload = OBJECT_MAPPER.readValue(in, ClientOverridesPayload.class);
            if (payload == null || payload.clients() == null) return Map.of();
            return payload.clients();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record ConceptAliases(List<String> aliasesEs, List<String> aliasesEn, List<String> exclusions) {}
    private record DictionaryPayload(Map<String, ConceptAliases> concepts) {}
    private record ClientOverridesPayload(Map<String, Map<String, ConceptAliases>> clients) {}
}
