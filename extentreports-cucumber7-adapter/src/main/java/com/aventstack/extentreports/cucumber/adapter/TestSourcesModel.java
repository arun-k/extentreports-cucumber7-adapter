package com.aventstack.extentreports.cucumber.adapter;

import io.cucumber.gherkin.Gherkin;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableRow;
import io.cucumber.plugin.event.TestSourceRead;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.cucumber.gherkin.Gherkin.makeSourceEnvelope;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

final class TestSourcesModel {

    private final Map<URI, TestSourceRead> pathToReadEventMap = new HashMap<>();
    private final Map<URI, GherkinDocument> pathToAstMap = new HashMap<>();
    private final Map<URI, Map<Long, AstNode>> pathToNodeMap = new HashMap<>();

    static Scenario getScenarioDefinition(AstNode astNode) {
        AstNode candidate = astNode;
        while (candidate != null && !(candidate.node instanceof Scenario)) {
            candidate = candidate.parent;
        }
        return candidate == null ? null : (Scenario) candidate.node;
    }

    static boolean isBackgroundStep(AstNode astNode) {
        return astNode.parent.node instanceof Background;
    }

    static String calculateId(AstNode astNode) {
        Object node = astNode.node;
        if (node instanceof Scenario) {
            return calculateId(astNode.parent) + ";" + convertToId(((Scenario) node).getName());
        }
        if (node instanceof ExamplesRowWrapperNode) {
            return calculateId(astNode.parent) + ";" + (((ExamplesRowWrapperNode) node).bodyRowIndex + 2);
        }
        if (node instanceof TableRow) {
            return calculateId(astNode.parent) + ";" + 1;
        }
        if (node instanceof Examples) {
            return calculateId(astNode.parent) + ";" + convertToId(((Examples) node).getName());
        }
        if (node instanceof Feature) {
            return convertToId(((Feature) node).getName());
        }
        return "";
    }

    static String convertToId(String name) {
        return name.replaceAll("[\\s'_,!]", "-").toLowerCase();
    }

    static URI relativize(URI uri) {
        if (!"file".equals(uri.getScheme())) {
            return uri;
        }
        if (!uri.isAbsolute()) {
            return uri;
        }

        try {
            URI root = new File("").toURI();
            URI relative = root.relativize(uri);
            // Scheme is lost by relativize
            return new URI("file", relative.getSchemeSpecificPart(), relative.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    void addTestSourceReadEvent(URI path, TestSourceRead event) {
        pathToReadEventMap.put(path, event);
    }

    Feature getFeature(URI path) {
        if (!pathToAstMap.containsKey(path)) {
            parseGherkinSource(path);
        }
        if (pathToAstMap.containsKey(path)) {
            return pathToAstMap.get(path).getFeature();
        }
        return null;
    }

    private void parseGherkinSource(URI path) {
        if (!pathToReadEventMap.containsKey(path)) {
            return;
        }
        String source = pathToReadEventMap.get(path).getSource();

        List<Envelope> sources = singletonList(
            makeSourceEnvelope(source, path.toString()));

        List<Envelope> envelopes = Gherkin.fromSources(
            sources,
            true,
            true,
            true,
            () -> String.valueOf(UUID.randomUUID())).collect(toList());

        GherkinDocument gherkinDocument = envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        pathToAstMap.put(path, gherkinDocument);
        Map<Long, AstNode> nodeMap = new HashMap<>();
        AstNode currentParent = new AstNode(gherkinDocument.getFeature(), null);
        for (FeatureChild child : gherkinDocument.getFeature().getChildren()) {
            processFeatureDefinition(nodeMap, child, currentParent);
        }
        pathToNodeMap.put(path, nodeMap);

    }

    private void processFeatureDefinition(Map<Long, AstNode> nodeMap, FeatureChild child, AstNode currentParent) {
        if (child.getBackground() != null) {
            processBackgroundDefinition(nodeMap, child.getBackground(), currentParent);
        } else if (child.getScenario() != null) {
            processScenarioDefinition(nodeMap, child.getScenario(), currentParent);
        } else if (child.getRule() != null) {
            AstNode childNode = new AstNode(child.getRule(), currentParent);
            nodeMap.put(child.getRule().getLocation().getLine(), childNode);
            for (RuleChild ruleChild : child.getRule().getChildren()) {
                processRuleDefinition(nodeMap, ruleChild, childNode);
            }
        }
    }

    private void processBackgroundDefinition(
            Map<Long, AstNode> nodeMap, Background background, AstNode currentParent
    ) {
        AstNode childNode = new AstNode(background, currentParent);
        nodeMap.put(background.getLocation().getLine(), childNode);
        for (Step step : background.getSteps()) {
            nodeMap.put(step.getLocation().getLine(), new AstNode(step, childNode));
        }
    }

    private void processScenarioDefinition(Map<Long, AstNode> nodeMap, Scenario child, AstNode currentParent) {
        AstNode childNode = new AstNode(child, currentParent);
        nodeMap.put(child.getLocation().getLine(), childNode);
        for (io.cucumber.messages.types.Step step : child.getSteps()) {
            nodeMap.put(step.getLocation().getLine(), new AstNode(step, childNode));
        }
        if (!child.getExamples().isEmpty()) {
            processScenarioOutlineExamples(nodeMap, child, childNode);
        }
    }

    private void processRuleDefinition(Map<Long, AstNode> nodeMap, RuleChild child, AstNode currentParent) {
        if (child.getBackground() != null) {
            processBackgroundDefinition(nodeMap, child.getBackground(), currentParent);
        } else if (child.getScenario() != null) {
            processScenarioDefinition(nodeMap, child.getScenario(), currentParent);
        }
    }

    private void processScenarioOutlineExamples(
            Map<Long, AstNode> nodeMap, Scenario scenarioOutline, AstNode parent
    ) {
        for (Examples examples : scenarioOutline.getExamples()) {
            AstNode examplesNode = new AstNode(examples, parent);
            TableRow headerRow = examples.getTableHeader();
            AstNode headerNode = new AstNode(headerRow, examplesNode);
            nodeMap.put(headerRow.getLocation().getLine(), headerNode);
            for (int i = 0; i < examples.getTableBody().size(); ++i) {
                TableRow examplesRow = examples.getTableBody().get(i);
                Object rowNode = new ExamplesRowWrapperNode(examplesRow, i);
                AstNode expandedScenarioNode = new AstNode(rowNode, examplesNode);
                nodeMap.put(examplesRow.getLocation().getLine(), expandedScenarioNode);
            }
        }
    }

    AstNode getAstNode(URI path, int line) {
        if (!pathToNodeMap.containsKey(path)) {
            parseGherkinSource(path);
        }
        if (pathToNodeMap.containsKey(path)) {
            return pathToNodeMap.get(path).get(Long.valueOf(line));
        }
        return null;
    }

    boolean hasBackground(URI path, int line) {
        if (!pathToNodeMap.containsKey(path)) {
            parseGherkinSource(path);
        }
        if (pathToNodeMap.containsKey(path)) {
            AstNode astNode = pathToNodeMap.get(path).get(Long.valueOf(line));
            return getBackgroundForTestCase(astNode).isPresent();
        }
        return false;
    }

    static Optional<Background> getBackgroundForTestCase(AstNode astNode) {
        Feature feature = getFeatureForTestCase(astNode);
        return feature.getChildren()
                .stream()
                .map(FeatureChild::getBackground)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static Feature getFeatureForTestCase(AstNode astNode) {
        while (astNode.parent != null) {
            astNode = astNode.parent;
        }
        return (Feature) astNode.node;
    }

    static class ExamplesRowWrapperNode {

        final int bodyRowIndex;

        ExamplesRowWrapperNode(Object examplesRow, int bodyRowIndex) {
            this.bodyRowIndex = bodyRowIndex;
        }

    }

    static class AstNode {

        final Object node;
        final AstNode parent;

        AstNode(Object node, AstNode parent) {
            this.node = node;
            this.parent = parent;
        }

    }

}