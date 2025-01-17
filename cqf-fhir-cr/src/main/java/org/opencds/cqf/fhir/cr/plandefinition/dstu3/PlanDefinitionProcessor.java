package org.opencds.cqf.fhir.cr.plandefinition.dstu3;

import static java.util.Objects.requireNonNull;

import ca.uhn.fhir.model.api.IElement;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hl7.fhir.dstu3.model.ActivityDefinition;
import org.hl7.fhir.dstu3.model.BooleanType;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleType;
import org.hl7.fhir.dstu3.model.CarePlan;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.DomainResource;
import org.hl7.fhir.dstu3.model.Enumerations;
import org.hl7.fhir.dstu3.model.Enumerations.FHIRAllTypes;
import org.hl7.fhir.dstu3.model.Extension;
import org.hl7.fhir.dstu3.model.Goal;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Library;
import org.hl7.fhir.dstu3.model.MetadataResource;
import org.hl7.fhir.dstu3.model.OperationOutcome;
import org.hl7.fhir.dstu3.model.Parameters;
import org.hl7.fhir.dstu3.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.dstu3.model.PlanDefinition;
import org.hl7.fhir.dstu3.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.dstu3.model.Questionnaire;
import org.hl7.fhir.dstu3.model.QuestionnaireResponse;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.RequestGroup;
import org.hl7.fhir.dstu3.model.RequestGroup.RequestGroupActionComponent;
import org.hl7.fhir.dstu3.model.RequestGroup.RequestGroupActionConditionComponent;
import org.hl7.fhir.dstu3.model.RequestGroup.RequestGroupActionRelatedActionComponent;
import org.hl7.fhir.dstu3.model.RequestGroup.RequestIntent;
import org.hl7.fhir.dstu3.model.RequestGroup.RequestStatus;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.Task;
import org.hl7.fhir.dstu3.model.Type;
import org.hl7.fhir.dstu3.model.UriType;
import org.hl7.fhir.dstu3.model.ValueSet;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.opencds.cqf.fhir.api.Repository;
import org.opencds.cqf.fhir.cql.CqfExpression;
import org.opencds.cqf.fhir.cql.EvaluationSettings;
import org.opencds.cqf.fhir.cr.activitydefinition.dstu3.ActivityDefinitionProcessor;
import org.opencds.cqf.fhir.cr.plandefinition.BasePlanDefinitionProcessor;
import org.opencds.cqf.fhir.cr.questionnaire.dstu3.generator.questionnaireitem.QuestionnaireItemGenerator;
import org.opencds.cqf.fhir.cr.questionnaire.dstu3.processor.QuestionnaireProcessor;
import org.opencds.cqf.fhir.cr.questionnaireresponse.dstu3.QuestionnaireResponseProcessor;
import org.opencds.cqf.fhir.utility.Constants;
import org.opencds.cqf.fhir.utility.dstu3.ContainedHelper;
import org.opencds.cqf.fhir.utility.dstu3.InputParameterResolver;
import org.opencds.cqf.fhir.utility.dstu3.PackageHelper;
import org.opencds.cqf.fhir.utility.dstu3.SearchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"unused", "squid:S107"})
public class PlanDefinitionProcessor extends BasePlanDefinitionProcessor<PlanDefinition> {

    private static final Logger logger = LoggerFactory.getLogger(PlanDefinitionProcessor.class);

    private final ActivityDefinitionProcessor activityDefinitionProcessor;
    private final QuestionnaireProcessor questionnaireProcessor;
    private final QuestionnaireResponseProcessor questionnaireResponseProcessor;
    private InputParameterResolver inputParameterResolver;
    private QuestionnaireItemGenerator questionnaireItemGenerator;

    protected OperationOutcome oc;

    public PlanDefinitionProcessor(Repository repository) {
        this(repository, EvaluationSettings.getDefault());
    }

    public PlanDefinitionProcessor(Repository repository, EvaluationSettings evaluationSettings) {
        super(repository, evaluationSettings);
        this.activityDefinitionProcessor = new ActivityDefinitionProcessor(this.repository, evaluationSettings);
        this.questionnaireProcessor = new QuestionnaireProcessor(this.repository, evaluationSettings);
        this.questionnaireResponseProcessor = new QuestionnaireResponseProcessor(this.repository, evaluationSettings);
    }

    @Override
    public void extractQuestionnaireResponse() {
        if (bundle == null) {
            return;
        }

        var questionnaireResponses = ((Bundle) bundle)
                .getEntry().stream()
                        .filter(entry -> entry.getResource()
                                .fhirType()
                                .equals(Enumerations.FHIRAllTypes.QUESTIONNAIRERESPONSE.toCode()))
                        .map(entry -> (QuestionnaireResponse) entry.getResource())
                        .collect(Collectors.toList());
        if (questionnaireResponses != null && !questionnaireResponses.isEmpty()) {
            for (var questionnaireResponse : questionnaireResponses) {
                try {
                    var extractBundle = (Bundle) questionnaireResponseProcessor.extract(
                            questionnaireResponse, parameters, bundle, libraryEngine);
                    extractedResources.add(questionnaireResponse);
                    for (var entry : extractBundle.getEntry()) {
                        ((Bundle) bundle).addEntry(entry);
                        // extractedResources.add(entry.getResource());
                    }
                } catch (Exception e) {
                    addOperationOutcomeIssue(
                            String.format("Error encountered extracting %s", questionnaireResponse.getId()));
                }
            }
        }
    }

    @Override
    public Bundle packagePlanDefinition(PlanDefinition thePlanDefinition, boolean theIsPut) {
        var bundle = new Bundle();
        bundle.setType(BundleType.TRANSACTION);
        bundle.addEntry(PackageHelper.createEntry(thePlanDefinition, theIsPut));
        // The CPG IG specifies a main cql library for a PlanDefinition
        var libraryCanonical = thePlanDefinition.hasLibrary()
                ? new StringType(thePlanDefinition.getLibrary().get(0).getReference())
                : null;
        if (libraryCanonical != null) {
            var library = (Library) SearchHelper.searchRepositoryByCanonical(repository, libraryCanonical);
            if (library != null) {
                bundle.addEntry(PackageHelper.createEntry(library, theIsPut));
                if (library.hasRelatedArtifact()) {
                    PackageHelper.addRelatedArtifacts(bundle, library.getRelatedArtifact(), repository, theIsPut);
                }
            }
        }
        if (thePlanDefinition.hasRelatedArtifact()) {
            PackageHelper.addRelatedArtifacts(bundle, thePlanDefinition.getRelatedArtifact(), repository, theIsPut);
        }

        return bundle;
    }

    @Override
    public <C extends IPrimitiveType<String>> PlanDefinition resolvePlanDefinition(
            IIdType theId, C theCanonical, IBaseResource thePlanDefinition) {
        var basePlanDefinition = thePlanDefinition;
        if (basePlanDefinition == null) {
            basePlanDefinition = theId != null
                    ? this.repository.read(PlanDefinition.class, theId)
                    : SearchHelper.searchRepositoryByCanonical(repository, theCanonical);
        }

        requireNonNull(basePlanDefinition, "Couldn't find PlanDefinition " + theId);

        return castOrThrow(
                        basePlanDefinition,
                        PlanDefinition.class,
                        "The planDefinition passed in was not a valid instance of PlanDefinition.class")
                .orElse(null);
    }

    @Override
    public PlanDefinition initApply(PlanDefinition planDefinition) {
        logger.info("Performing $apply operation on {}", planDefinition.getIdPart());

        oc = new OperationOutcome();
        oc.setId("apply-outcome-" + planDefinition.getIdPart());

        extractQuestionnaireResponse();

        this.questionnaire = new Questionnaire();
        this.questionnaire.setId(new IdType(FHIRAllTypes.QUESTIONNAIRE.toCode(), planDefinition.getIdPart()));
        this.questionnaireItemGenerator =
                QuestionnaireItemGenerator.of(repository, subjectId, parameters, bundle, libraryEngine);
        this.inputParameterResolver = new InputParameterResolver(
                subjectId, encounterId, practitionerId, parameters, useServerData, bundle, repository);

        return planDefinition;
    }

    @Override
    public IBaseResource applyPlanDefinition(PlanDefinition planDefinition) {
        // Each Group of actions shares a RequestGroup
        var canonical = planDefinition.getUrl();
        if (planDefinition.hasVersion()) {
            canonical = String.format("%s|%s", canonical, planDefinition.getVersion());
        }
        var requestGroup = new RequestGroup()
                .setStatus(RequestStatus.DRAFT)
                .setIntent(RequestIntent.PROPOSAL)
                .addDefinition(new Reference(canonical))
                .setSubject(new Reference(subjectId));
        requestGroup.setId(new IdType(
                requestGroup.fhirType(), planDefinition.getIdElement().getIdPart()));
        // requestGroup.setMeta(new Meta().addProfile(Constants.CPG_STRATEGY));
        if (encounterId != null) {
            requestGroup.setContext(new Reference(encounterId));
        }
        if (practitionerId != null) {
            requestGroup.setAuthor(new Reference(practitionerId));
        }
        if (organizationId != null) {
            requestGroup.setAuthor(new Reference(organizationId));
        }
        if (userLanguage instanceof CodeableConcept) {
            requestGroup.setLanguage(
                    ((CodeableConcept) userLanguage).getCodingFirstRep().getCode());
        }

        if (planDefinition.hasExtension()) {
            requestGroup.setExtension(planDefinition.getExtension().stream()
                    .filter(e -> !EXCLUDED_EXTENSION_LIST.contains(e.getUrl()))
                    .collect(Collectors.toList()));
        }

        var defaultLibraryUrl = planDefinition.getLibrary() == null
                        || planDefinition.getLibrary().isEmpty()
                ? null
                : planDefinition.getLibrary().get(0).getReference();
        // Extension resolution is not supported in Dstu3

        for (int i = 0; i < planDefinition.getGoal().size(); i++) {
            var goal = convertGoal(planDefinition.getGoal().get(i));
            if (Boolean.TRUE.equals(containResources)) {
                requestGroup.addContained(goal);
            } else {
                goal.setIdElement(new IdType("Goal", String.valueOf(i + 1)));
                requestGroup
                        .addExtension()
                        .setUrl(Constants.PERTAINS_TO_GOAL)
                        .setValue(new Reference(goal.getIdElement()));
            }
            // Always add goals to the resource list so they can be added to the CarePlan if needed
            requestResources.add(goal);
        }

        // Create Questionnaire for the RequestGroup if using Modular Questionnaires.
        // Assuming Dynamic until a use case for modular arises

        var metConditions = new HashMap<String, PlanDefinition.PlanDefinitionActionComponent>();

        for (var action : planDefinition.getAction()) {
            // TODO - Apply input/output dataRequirements?
            requestGroup.addAction(
                    resolveAction(defaultLibraryUrl, planDefinition, requestGroup, metConditions, action));
        }

        return Boolean.TRUE.equals(containResources)
                ? ContainedHelper.liftContainedResourcesToParent(requestGroup)
                : requestGroup;
    }

    @Override
    public CarePlan transformToCarePlan(IBaseResource rg) {
        RequestGroup requestGroup = (RequestGroup) rg;
        if (!oc.getIssue().isEmpty()) {
            requestGroup.addContained(oc);
            requestGroup.addExtension(Constants.EXT_CRMI_MESSAGES, new Reference("#" + oc.getIdPart()));
        }
        var carePlan = new CarePlan()
                .setDefinition(requestGroup.getDefinition())
                .setSubject(requestGroup.getSubject())
                .setStatus(CarePlan.CarePlanStatus.DRAFT)
                .setIntent(CarePlan.CarePlanIntent.PROPOSAL);
        carePlan.setId(
                new IdType(carePlan.fhirType(), requestGroup.getIdElement().getIdPart()));

        if (requestGroup.hasContext()) {
            carePlan.setContext(requestGroup.getContext());
        }
        if (requestGroup.hasAuthor()) {
            carePlan.setAuthor(Collections.singletonList(requestGroup.getAuthor()));
        }
        if (requestGroup.getLanguage() != null) {
            carePlan.setLanguage(requestGroup.getLanguage());
        }
        for (var goal : requestResources) {
            if (goal.fhirType().equals("Goal")) {
                carePlan.addGoal(new Reference((Resource) goal));
            }
        }

        var operationOutcomes = resolveContainedByType(requestGroup, FHIRAllTypes.OPERATIONOUTCOME.toCode());
        for (var operationOutcome : operationOutcomes) {
            carePlan.addExtension(Constants.EXT_CRMI_MESSAGES, new Reference("#" + operationOutcome.getId()));
        }

        carePlan.addActivity().setReference(new Reference(requestGroup));
        carePlan.addContained(requestGroup);

        for (var resource : extractedResources) {
            carePlan.addSupportingInfo(new Reference((Resource) resource));
            carePlan.addContained((Resource) resource);
        }

        if (((Questionnaire) this.questionnaire).hasItem()) {
            carePlan.addContained((Resource) this.questionnaire);
        }

        return (CarePlan) ContainedHelper.liftContainedResourcesToParent(carePlan);
    }

    @Override
    public IBaseResource transformToBundle(IBaseResource rg) {
        return null;
    }

    @Override
    public void addOperationOutcomeIssue(String issue) {
        oc.addIssue()
                .setCode(OperationOutcome.IssueType.EXCEPTION)
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setDiagnostics(issue);
    }

    private Goal convertGoal(PlanDefinition.PlanDefinitionGoalComponent goal) {
        var myGoal = new Goal();
        myGoal.setCategory(Collections.singletonList(goal.getCategory()));
        myGoal.setDescription(goal.getDescription());
        myGoal.setPriority(goal.getPriority());
        myGoal.setStart(goal.getStart());

        var goalTarget = goal.hasTarget()
                ? goal.getTarget().stream()
                        .map(target -> {
                            var myTarget = new Goal.GoalTargetComponent();
                            myTarget.setDetail(target.getDetail());
                            myTarget.setMeasure(target.getMeasure());
                            myTarget.setDue(target.getDue());
                            myTarget.setExtension(target.getExtension());
                            return myTarget;
                        })
                        .collect(Collectors.toList())
                        .get(0)
                : null;
        myGoal.setTarget(goalTarget);
        return myGoal;
    }

    private RequestGroupActionComponent resolveAction(
            String defaultLibraryUrl,
            PlanDefinition planDefinition,
            RequestGroup requestGroup,
            Map<String, PlanDefinition.PlanDefinitionActionComponent> metConditions,
            PlanDefinition.PlanDefinitionActionComponent action) {
        if (planDefinition.hasExtension(Constants.CPG_QUESTIONNAIRE_GENERATE) && action.hasInput()) {
            for (var actionInput : action.getInput()) {
                if (actionInput.hasProfile()) {
                    ((Questionnaire) this.questionnaire)
                            .addItem(this.questionnaireItemGenerator.generateItem(
                                    actionInput,
                                    ((Questionnaire) this.questionnaire)
                                            .getItem()
                                            .size()));
                }
            }
        }

        if (Boolean.TRUE.equals(meetsConditions(defaultLibraryUrl, action))) {
            // TODO: Figure out why this was here and what it was trying to do
            // if (action.hasRelatedAction()) {
            // for (var relatedActionComponent : action.getRelatedAction()) {
            // if (relatedActionComponent.getRelationship().equals(ActionRelationshipType.AFTER)
            // && metConditions.containsKey(relatedActionComponent.getActionId())) {
            // metConditions.put(action.getId(), action);
            // resolveDefinition(planDefinition, requestGroup, action);
            // resolveDynamicValues(planDefinition, requestGroup, action);
            // }
            // }
            // }
            metConditions.put(action.getId(), action);
            var requestAction = createRequestAction(action);
            // Extension resolution is not supported in Dstu3
            if (action.hasAction()) {
                for (var containedAction : action.getAction()) {
                    requestAction.addAction(resolveAction(
                            defaultLibraryUrl, planDefinition, requestGroup, metConditions, containedAction));
                }
            }
            IBaseResource resource = null;
            if (action.hasDefinition()) {
                resource = resolveDefinition(planDefinition, action);
                if (resource != null) {
                    applyAction(requestGroup, resource, action);
                    requestAction.setResource(new Reference(resource.getIdElement()));
                    if (Boolean.TRUE.equals(containResources)) {
                        requestGroup.addContained((Resource) resource);
                    } else {
                        requestResources.add(resource);
                    }
                }
            }
            resolveDynamicValues(defaultLibraryUrl, requestAction, resource, action);

            return requestAction;
        }

        return null;
    }

    private RequestGroupActionComponent createRequestAction(PlanDefinitionActionComponent action) {
        var requestAction = new RequestGroupActionComponent()
                .setTitle(action.getTitle())
                .setDescription(action.getDescription())
                .setTextEquivalent(action.getTextEquivalent())
                .setCode(action.getCode())
                .setDocumentation(action.getDocumentation())
                .setTiming(action.getTiming())
                .setType(action.getType());
        requestAction.setId(action.getId());
        requestAction.setExtension(action.getExtension());

        if (action.hasCondition()) {
            action.getCondition()
                    .forEach(c -> requestAction.addCondition(new RequestGroupActionConditionComponent()
                            .setKind(RequestGroup.ActionConditionKind.fromCode(
                                    c.getKind().toCode()))
                            .setExpression(c.getExpression())));
        }
        if (action.hasRelatedAction()) {
            action.getRelatedAction()
                    .forEach(ra -> requestAction.addRelatedAction(new RequestGroupActionRelatedActionComponent()
                            .setActionId(ra.getActionId())
                            .setRelationship(RequestGroup.ActionRelationshipType.fromCode(
                                    ra.getRelationship().toCode()))
                            .setOffset(ra.getOffset())));
        }
        if (action.hasSelectionBehavior()) {
            requestAction.setSelectionBehavior(RequestGroup.ActionSelectionBehavior.fromCode(
                    action.getSelectionBehavior().toCode()));
        }

        return requestAction;
    }

    private IBaseResource resolveDefinition(
            PlanDefinition planDefinition, PlanDefinition.PlanDefinitionActionComponent action) {
        logger.debug("Resolving definition {}", action.getDefinition().getReference());
        var definition = new StringType(action.getDefinition().getReference());
        var resourceName = resolveResourceName(definition, planDefinition);
        switch (FHIRAllTypes.fromCode(requireNonNull(resourceName))) {
            case PLANDEFINITION:
                return applyNestedPlanDefinition(planDefinition, definition);
            case ACTIVITYDEFINITION:
                return applyActivityDefinition(planDefinition, definition);
            case QUESTIONNAIRE:
                return applyQuestionnaireDefinition(planDefinition, definition);
            default:
                throw new FHIRException(String.format("Unknown action definition: %s", definition));
        }
    }

    private IBaseResource applyQuestionnaireDefinition(PlanDefinition planDefinition, StringType definition) {
        IBaseResource result = null;
        try {
            var referenceToContained = definition.getValue().startsWith("#");
            if (referenceToContained) {
                result = resolveContained(planDefinition, definition.getValue());
            } else {
                result = SearchHelper.searchRepositoryByCanonical(repository, definition);
            }
        } catch (Exception e) {
            var message = String.format(
                    "ERROR: Questionnaire %s could not be applied and threw exception %s",
                    definition.asStringValue(), e.toString());
            logger.error(message);
            addOperationOutcomeIssue(message);
        }

        return result;
    }

    private IBaseResource applyActivityDefinition(PlanDefinition planDefinition, StringType definition) {
        IBaseResource result = null;
        try {
            var referenceToContained = definition.getValue().startsWith("#");
            var activityDefinition = (ActivityDefinition)
                    (referenceToContained
                            ? resolveContained(planDefinition, definition.getValue())
                            : SearchHelper.searchRepositoryByCanonical(repository, definition));
            result = this.activityDefinitionProcessor.apply(
                    activityDefinition,
                    subjectId,
                    encounterId,
                    practitionerId,
                    organizationId,
                    userType,
                    userLanguage,
                    userTaskContext,
                    setting,
                    settingContext,
                    parameters,
                    useServerData,
                    bundle,
                    libraryEngine);
            result.setId(
                    referenceToContained
                            ? new IdType(
                                    result.fhirType(),
                                    activityDefinition.getIdPart().replaceFirst("#", ""))
                            : activityDefinition.getIdElement().withResourceType(result.fhirType()));
        } catch (Exception e) {
            var message = String.format(
                    "ERROR: ActivityDefinition %s could not be applied and threw exception %s",
                    definition.asStringValue(), e.toString());
            logger.error(message);
            addOperationOutcomeIssue(message);
        }

        return result;
    }

    private IBaseResource applyNestedPlanDefinition(PlanDefinition planDefinition, StringType definition) {
        RequestGroup result = null;
        try {
            var referenceToContained = definition.getValue().startsWith("#");
            var nextPlanDefinition = (PlanDefinition)
                    (referenceToContained
                            ? resolveContained(planDefinition, definition.getValue())
                            : SearchHelper.searchRepositoryByCanonical(repository, definition));
            result = (RequestGroup) applyPlanDefinition(nextPlanDefinition);
        } catch (Exception e) {
            var message = String.format(
                    "ERROR: PlanDefinition %s could not be applied and threw exception %s",
                    definition.asStringValue(), e.toString());
            logger.error(message);
            addOperationOutcomeIssue(message);
        }

        return result;
    }

    private void applyAction(
            RequestGroup requestGroup, IBaseResource result, PlanDefinition.PlanDefinitionActionComponent action) {
        if ("Task".equals(result.fhirType())) {
            resolveTask(requestGroup, (Task) result, action);
        }
    }

    /*
     * offset -> Duration timing -> Timing ( just our use case for connectathon period periodUnit
     * frequency count ) use task code
     */
    private void resolveTask(
            RequestGroup requestGroup, Task task, PlanDefinition.PlanDefinitionActionComponent action) {
        if (action.hasId()) {
            task.setId(new IdType(task.fhirType(), action.getId()));
        }
        if (action.hasRelatedAction()) {
            var relatedActions = action.getRelatedAction();
            for (var relatedAction : relatedActions) {
                var next = new Extension();
                next.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/next");
                if (relatedAction.hasOffset()) {
                    var offsetExtension = new Extension();
                    offsetExtension.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/offset");
                    offsetExtension.setValue(relatedAction.getOffset());
                    next.addExtension(offsetExtension);
                }
                var target = new Extension();
                var targetRef = new Reference(new IdType(task.fhirType(), relatedAction.getActionId()));
                target.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/target");
                target.setValue(targetRef);
                next.addExtension(target);
                task.addExtension(next);
            }
        }

        if (action.hasCondition()) {
            var conditionComponents = action.getCondition();
            for (var conditionComponent : conditionComponents) {
                var condition = new Extension();
                condition.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/condition");
                var language = new Extension();
                language.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/language");
                language.setValue(new StringType(conditionComponent.getLanguage()));
                condition.addExtension(language);
                var expression = new Extension();
                expression.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/expression");
                expression.setValue(new StringType(conditionComponent.getExpression()));
                condition.addExtension(expression);
                task.addExtension(condition);
            }
        }

        if (action.hasInput()) {
            var dataRequirements = action.getInput();
            for (var dataRequirement : dataRequirements) {
                var input = new Extension();
                input.setUrl("http://hl7.org/fhir/aphl/StructureDefinition/input");
                input.setValue(dataRequirement);
                task.addExtension(input);
            }
        }

        task.addBasedOn(new Reference(requestGroup));
        task.setFor(requestGroup.getSubject());

        resolvePrepopulateAction(action, requestGroup, task);
    }

    private void resolvePrepopulateAction(
            PlanDefinition.PlanDefinitionActionComponent action, RequestGroup requestGroup, Task task) {
        if (action.hasExtension(Constants.SDC_QUESTIONNAIRE_PREPOPULATE)) {
            var questionnaireBundles =
                    getQuestionnairePackage(action.getExtensionByUrl(Constants.SDC_QUESTIONNAIRE_PREPOPULATE));
            for (var questionnaireBundle : questionnaireBundles) {
                var toPopulate =
                        (Questionnaire) questionnaireBundle.getEntryFirstRep().getResource();
                // Bundle should contain a Questionnaire and supporting Library and ValueSet resources
                var libraries = questionnaireBundle.getEntry().stream()
                        .filter(e -> e.hasResource()
                                && (e.getResource().fhirType().equals(Enumerations.FHIRAllTypes.LIBRARY.toCode())))
                        .map(e -> (Library) e.getResource())
                        .collect(Collectors.toList());
                var valueSets = questionnaireBundle.getEntry().stream()
                        .filter(e -> e.hasResource()
                                && (e.getResource().fhirType().equals(Enumerations.FHIRAllTypes.VALUESET.toCode())))
                        .map(e -> (ValueSet) e.getResource())
                        .collect(Collectors.toList());
                var additionalData =
                        bundle == null ? new Bundle().setType(BundleType.COLLECTION) : ((Bundle) bundle).copy();
                libraries.forEach(
                        library -> additionalData.addEntry(new Bundle.BundleEntryComponent().setResource(library)));
                valueSets.forEach(
                        valueSet -> additionalData.addEntry(new Bundle.BundleEntryComponent().setResource(valueSet)));

                var populatedQuestionnaire = questionnaireProcessor.prePopulate(
                        toPopulate, subjectId, this.parameters, additionalData, libraryEngine);
                if (Boolean.TRUE.equals(containResources)) {
                    requestGroup.addContained(populatedQuestionnaire);
                } else {
                    requestResources.add(populatedQuestionnaire);
                }
                task.setFocus(new Reference(
                        new IdType(FHIRAllTypes.QUESTIONNAIRE.toCode(), populatedQuestionnaire.getIdPart())));
                task.setFor(requestGroup.getSubject());
            }
        }
    }

    private List<Bundle> getQuestionnairePackage(Extension prepopulateExtension) {
        Bundle bundle = null;
        // PlanDef action should provide endpoint for $questionnaire-for-order operation as well as
        // the order id to pass
        var parameterExtension =
                prepopulateExtension.getExtensionByUrl(Constants.SDC_QUESTIONNAIRE_PREPOPULATE_PARAMETER);
        if (parameterExtension == null) {
            throw new IllegalArgumentException(String.format(
                    "Required extension for %s not found.", Constants.SDC_QUESTIONNAIRE_PREPOPULATE_PARAMETER));
        }
        var parameterName = parameterExtension.getValue().toString();
        var prepopulateParameter = this.parameters != null
                ? ((Parameters) this.parameters)
                        .getParameter().stream()
                                .filter(p -> p.getName().equals(parameterName))
                                .collect(Collectors.toList())
                                .get(0)
                : null;
        if (prepopulateParameter == null) {
            throw new IllegalArgumentException(String.format("Parameter not found: %s ", parameterName));
        }
        var orderId = prepopulateParameter.toString();

        var questionnaireExtension =
                prepopulateExtension.getExtensionByUrl(Constants.SDC_QUESTIONNAIRE_LOOKUP_QUESTIONNAIRE);
        if (questionnaireExtension == null) {
            throw new IllegalArgumentException(String.format(
                    "Required extension for %s not found.", Constants.SDC_QUESTIONNAIRE_LOOKUP_QUESTIONNAIRE));
        }

        if (questionnaireExtension.getValue().hasType(FHIRAllTypes.URI.toCode())) {
            var questionnaire =
                    SearchHelper.searchRepositoryByCanonical(repository, (UriType) questionnaireExtension.getValue());
            if (questionnaire != null) {
                bundle = new Bundle().addEntry(new Bundle.BundleEntryComponent().setResource(questionnaire));
            }
        } else if (questionnaireExtension.getValue().hasType(FHIRAllTypes.STRING.toCode())) {
            // Assuming package operation endpoint if the extension is using valueUrl instead of
            // valueCanonical
            bundle = callQuestionnairePackageOperation(
                    ((StringType) questionnaireExtension.getValue()).getValueAsString());
        }

        if (bundle == null) {
            bundle = new Bundle();
        }

        return Collections.singletonList(bundle);
    }

    private Bundle callQuestionnairePackageOperation(String url) {
        String baseUrl = null;
        String operation = null;
        if (url.contains("$")) {
            var urlSplit = url.split("$");
            baseUrl = urlSplit[0];
            operation = urlSplit[1];
        } else {
            baseUrl = url;
            operation = "questionnaire-package";
        }

        Bundle bundle = null;
        IGenericClient client = org.opencds.cqf.fhir.utility.client.Clients.forUrl(repository.fhirContext(), baseUrl);
        // Clients.registerBasicAuth(client, user, password);
        try {
            // TODO: This is not currently in use, but if it ever is we will need to determine how the
            // order and coverage resources are passed in
            Type order = null;
            Type coverage = null;
            bundle = client.operation()
                    .onType(FHIRAllTypes.QUESTIONNAIRE.toCode())
                    .named('$' + operation)
                    .withParameters(new Parameters()
                            .addParameter(new ParametersParameterComponent()
                                    .setName("order")
                                    .setValue(order))
                            .addParameter(new ParametersParameterComponent()
                                    .setName("coverage")
                                    .setValue(coverage)))
                    .returnResourceType(Bundle.class)
                    .execute();
        } catch (Exception e) {
            logger.error("Error encountered calling $questionnaire-package operation: %s", e);
        }

        return bundle;
    }

    private void resolveDynamicValues(
            String defaultLibraryUrl,
            IElement requestAction,
            IBase resource,
            PlanDefinition.PlanDefinitionActionComponent action) {
        if (!action.hasDynamicValue()) {
            return;
        }
        var inputParams = inputParameterResolver.resolveInputParameters(action.getInput());
        action.getDynamicValue().forEach(dynamicValue -> {
            if (dynamicValue.hasExpression()) {
                List<IBase> result = null;
                try {
                    result = libraryEngine.resolveExpression(
                            subjectId,
                            new CqfExpression(
                                    dynamicValue.getLanguage(), dynamicValue.getExpression(), defaultLibraryUrl),
                            inputParams,
                            bundle);
                    resolveDynamicValue(result, dynamicValue.getPath(), requestAction, resource);
                } catch (Exception e) {
                    var message = String.format(
                            "DynamicValue expression %s encountered exception: %s",
                            dynamicValue.getExpression(), e.getMessage());
                    logger.error(message);
                    addOperationOutcomeIssue(message);
                }
            }
        });
    }

    private Boolean meetsConditions(String defaultLibraryUrl, PlanDefinition.PlanDefinitionActionComponent action) {
        if (!action.hasCondition()) {
            return true;
        }
        var inputParams = inputParameterResolver.resolveInputParameters(action.getInput());
        for (var condition : action.getCondition()) {
            if (condition.hasExpression()) {
                IBase result = null;
                try {
                    var results = libraryEngine.resolveExpression(
                            subjectId,
                            new CqfExpression(condition.getLanguage(), condition.getExpression(), defaultLibraryUrl),
                            inputParams,
                            bundle);
                    result = results == null || results.isEmpty() ? null : results.get(0);
                } catch (Exception e) {
                    var message = String.format(
                            "Condition expression %s encountered exception: %s",
                            condition.getExpression(), e.getMessage());
                    logger.error(message);
                    addOperationOutcomeIssue(message);
                }
                if (result == null) {
                    logger.warn("Condition expression {} returned null", condition.getExpression());
                    return false;
                }
                if (!(result instanceof BooleanType)) {
                    logger.warn(
                            "The condition expression {} returned a non-boolean value: {}",
                            condition.getExpression(),
                            result.getClass().getSimpleName());
                    continue;
                }
                if (!((BooleanType) result).booleanValue()) {
                    logger.debug("The result of condition expression {} is false", condition.getExpression());
                    return false;
                }
                logger.debug("The result of condition expression {} is true", condition.getExpression());
            }
        }
        return true;
    }

    protected String resolveResourceName(StringType canonical, MetadataResource resource) {
        if (canonical.hasValue()) {
            var id = canonical.getValue();
            if (id.contains("/")) {
                id = id.replace(id.substring(id.lastIndexOf("/")), "");
                return id.contains("/") ? id.substring(id.lastIndexOf("/") + 1) : id;
            } else if (id.startsWith("#")) {
                return resolveContained(resource, id).getResourceType().name();
            }
            return null;
        }

        throw new FHIRException("CanonicalType must have a value for resource name extraction");
    }

    protected Resource resolveContained(DomainResource resource, String id) {
        var first = resource.getContained().stream()
                .filter(Resource::hasIdElement)
                .filter(x -> x.getIdElement().getIdPart().equals(id))
                .findFirst();
        return first.orElse(null);
    }

    protected List<Resource> resolveContainedByType(DomainResource resource, String resourceType) {
        return resource.getContained().stream()
                .filter(r -> r.fhirType().equals(resourceType))
                .collect(Collectors.toList());
    }
}
