package fr.techad.sonar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.text.StrSubstitutor;
import org.jetbrains.annotations.NotNull;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputPath;
import org.sonar.api.batch.postjob.PostJob;
import org.sonar.api.batch.postjob.PostJobContext;
import org.sonar.api.batch.postjob.PostJobDescriptor;
import org.sonar.api.batch.postjob.issue.PostJobIssue;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import fr.techad.sonar.gerrit.GerritFacade;
import fr.techad.sonar.gerrit.factory.GerritFacadeFactory;
import fr.techad.sonar.gerrit.review.ReviewFileComment;
import fr.techad.sonar.gerrit.review.ReviewInput;
import fr.techad.sonar.gerrit.review.ReviewLineComment;



public class GerritPostJob implements PostJob {
    private static final Logger LOG = Loggers.get(GerritPostJob.class);
    private final Settings settings;
    private final GerritConfiguration gerritConfiguration;
    private List<String> gerritModifiedFiles;
    private GerritFacade gerritFacade;
    private ReviewInput reviewInput = ReviewHolder.getReviewInput();






    public GerritPostJob(Settings settings, GerritConfiguration gerritConfiguration,
            GerritFacadeFactory gerritFacadeFactory) {
        LOG.debug("[GERRIT PLUGIN] Instanciating GerritPostJob");
        this.settings = settings;
        this.gerritFacade = gerritFacadeFactory.getFacade();
        this.gerritConfiguration = gerritConfiguration;

    }

    @Override
    public void describe(PostJobDescriptor descriptor) {
        descriptor.name("GERRIT PLUGIN");
        descriptor.requireProperty(PropertyKey.GERRIT_CHANGE_ID);
    }

    @Override
    public void execute(PostJobContext postJobContext) {
        if (!gerritConfiguration.isEnabled()) {
            LOG.info("[GERRIT PLUGIN] PostJob : analysis has finished. Plugin is disabled. No actions taken.");
            return;
        }

        Map<InputPath, List<PostJobIssue>> issueMap = new HashMap<>();
        for (PostJobIssue i : postJobContext.issues()) {
            InputComponent inputComponent = i.inputComponent();
            if (inputComponent instanceof InputPath) {
                InputPath inputPath = (InputPath) inputComponent;
                List<PostJobIssue> l = issueMap.get(inputPath);
                if (l == null) {
                    l = new ArrayList<>();
                    issueMap.put(inputPath, l);
                }
                l.add(i);
            }
        }

        for (Map.Entry<InputPath, List<PostJobIssue>> e : issueMap.entrySet()) {
            decorate(e.getKey(), postJobContext, e.getValue());
        }

        try {
            LOG.info("[GERRIT PLUGIN] Analysis has finished. Sending results to Gerrit.");
            reviewInput.setMessage(createMessage(gerritConfiguration.getMessage(), settings));

            LOG.debug("[GERRIT PLUGIN] Define message : {}", reviewInput.getMessage());
            LOG.debug("[GERRIT PLUGIN] Number of comments : {}", reviewInput.size());

            int maxLevel = reviewInput.maxLevelSeverity();
            LOG.debug("[GERRIT PLUGIN] Configured threshold {}, max review level {}",
                    gerritConfiguration.getThreshold(), valueToThreshold(maxLevel));

            if (reviewInput.isEmpty()) {
                LOG.debug("[GERRIT PLUGIN] No issues ! Vote {} for the label : {}",
                        gerritConfiguration.getVoteNoIssue(), gerritConfiguration.getLabel());
                reviewInput.setValueAndLabel(gerritConfiguration.getVoteNoIssue(), gerritConfiguration.getLabel());
            } else if (maxLevel < thresholdToValue(gerritConfiguration.getThreshold())) {
                LOG.debug("[GERRIT PLUGIN] Issues below threshold. Vote {} for the label : {}",
                        gerritConfiguration.getVoteBelowThreshold(), gerritConfiguration.getLabel());
                reviewInput.setValueAndLabel(gerritConfiguration.getVoteBelowThreshold(),
                        gerritConfiguration.getLabel());
            } else {
                LOG.debug("[GERRIT PLUGIN] Issues above threshold. Vote {} for the label : {}",
                        gerritConfiguration.getVoteAboveThreshold(), gerritConfiguration.getLabel());
                reviewInput.setValueAndLabel(gerritConfiguration.getVoteAboveThreshold(),
                        gerritConfiguration.getLabel());
            }

            LOG.debug("[GERRIT PLUGIN] Send review for ChangeId={}, RevisionId={}", gerritConfiguration.getChangeId(),
                    gerritConfiguration.getRevisionId());

            gerritFacade.setReview(reviewInput);

        } catch (GerritPluginException e) {
            LOG.error("[GERRIT PLUGIN] Error sending review to Gerrit", e);
        }
    }

    protected void decorate(InputPath resource, PostJobContext context, Collection<PostJobIssue> issues) {
        LOG.debug("[GERRIT PLUGIN] Decorate: {}", resource.relativePath());
        if (!resource.file().isFile()) {
            LOG.debug("[GERRIT PLUGIN] {} is not a file", resource.relativePath());
            return;
        }

        try {
            LOG.debug("[GERRIT PLUGIN] Start Sonar decoration for Gerrit");
            assertOrFetchGerritModifiedFiles();
        } catch (GerritPluginException e) {
            LOG.error("[GERRIT PLUGIN] Error getting Gerrit datas", e);
        }

        LOG.debug("[GERRIT PLUGIN] Look for in Gerrit if the file was under review, resource={}", resource);
        LOG.debug("[GERRIT PLUGIN] Look for in Gerrit if the file was under review, name={}", resource.relativePath());
        LOG.debug("[GERRIT PLUGIN] Look for in Gerrit if the file was under review, key={}", resource.key());

        String filename = getFileNameFromInputPath(resource);
        if (filename != null) {
            LOG.info("[GERRIT PLUGIN] Found a match between Sonar and Gerrit for {}: ", resource.relativePath(),
                    filename);
            processFileResource(filename, issues);
        }
    }

    protected void assertOrFetchGerritModifiedFiles() throws GerritPluginException {
        if (gerritModifiedFiles != null) {
            return;
        }
        gerritModifiedFiles = gerritFacade.listFiles();
        LOG.debug("[GERRIT PLUGIN] Modified files in gerrit : {}", gerritModifiedFiles);
    }

    protected ReviewLineComment issueToComment(PostJobIssue issue) {
        ReviewLineComment result = new ReviewLineComment();

        result.setLine(issue.line());
        result.setSeverity(thresholdToValue(issue.severity().toString()));

        result.setMessage(createIssueMessage(gerritConfiguration.getIssueComment(), settings, issue));
        LOG.debug("[GERRIT PLUGIN] issueToComment {}", result.toString());
        return result;
    }

    protected void processFileResource(@NotNull String file, @NotNull Collection<PostJobIssue> issuable) {
        List<ReviewFileComment> comments = new ArrayList<>();
        commentIssues(issuable, comments);
        if (!comments.isEmpty()) {
            reviewInput.addComments(file, comments);
        }
    }

    private void commentIssues(Collection<PostJobIssue> issues, List<ReviewFileComment> comments) {
        LOG.info("[GERRIT PLUGIN] Found {} issues", issues.size());

        for (PostJobIssue issue : issues) {
            if (gerritConfiguration.shouldCommentNewIssuesOnly() && !issue.isNew()) {
                LOG.info(
                        "[GERRIT PLUGIN] Issue is not new and only new one should be commented. Will not push back to Gerrit.");
            } else {
                comments.add(issueToComment(issue));
            }
        }
    }

    private String getFileNameFromInputPath(InputPath resource) {
        String filename = null;
        if (gerritModifiedFiles.contains(resource.relativePath())) {
            LOG.info("[GERRIT PLUGIN] Found a match between Sonar and Gerrit for {}", resource.relativePath());
            filename = resource.relativePath();
        } else if (gerritModifiedFiles.contains(gerritFacade.parseFileName(resource.relativePath()))) {
            LOG.info("[GERRIT PLUGIN] Found a match between Sonar and Gerrit for {}",
                    gerritFacade.parseFileName(resource.relativePath()));
            filename = gerritFacade.parseFileName(resource.relativePath());
        } else {
            LOG.debug("[GERRIT PLUGIN] Parse the Gerrit List to look for the resource: {}", resource.relativePath());
            // Loop on each item
            for (String fileGerrit : gerritModifiedFiles) {
                if (gerritFacade.parseFileName(fileGerrit).equals(resource.relativePath())) {
                    filename = fileGerrit;
                    break;
                }
            }
        }
        if (filename == null) {
            LOG.debug("[GERRIT PLUGIN] File '{}' was not found in the review list)", resource.relativePath());
            LOG.debug("[GERRIT PLUGIN] Try to find with: '{}', '{}' and '{}'", resource.relativePath(),
                    gerritFacade.parseFileName(resource.relativePath()));
        }
        return filename;
    }


    //Taken from messageutils


    private static final String ISSUE_PREFIX = "issue";
    private static final String ISSUE_IS_NEW = "isNew";
    private static final String ISSUE_RULE_KEY = "ruleKey";
    private static final String ISSUE_SEVERITY = "severity";
    private static final String ISSUE_MESSAGE = "message";
    private static final String ISSUE_SEPARATOR = ".";

    /**
     * Create the issue message. The variables contained in the original message
     * are replaced by the settings values and the issue data.
     *
     * @param originalMessage
     *            the original message
     * @param settings
     *            the settings
     * @param issue
     *            the issue
     * @return a new message with the replaced variables by data.
     */
    public String createIssueMessage(String originalMessage, Settings settings, PostJobIssue issue) {
        HashMap<String, Object> valueMap = new HashMap<>();
        valueMap.put(prefixKey(ISSUE_PREFIX, ISSUE_IS_NEW), issue.isNew());
        valueMap.put(prefixKey(ISSUE_PREFIX, ISSUE_RULE_KEY), issue.ruleKey());
        valueMap.put(prefixKey(ISSUE_PREFIX, ISSUE_SEVERITY), issue.severity());
        valueMap.put(prefixKey(ISSUE_PREFIX, ISSUE_MESSAGE), issue.message());
        return substituteProperties(originalMessage, settings, valueMap);
    }

    /**
     * Create the message from originalMessage and Settings. The variables
     * contained in the originalMessage are replaced by the Settings value
     *
     * @param originalMessage
     *            the original message which contains variables
     * @param settings
     *            the settings
     * @return a new string with substituted variables.
     */
    public String createMessage(String originalMessage, Settings settings) {
        return substituteProperties(originalMessage, settings, Collections.<String, Object>emptyMap());
    }

    /**
     * Build a string based on an original string and the replacement by
     * settings and map values
     *
     * @param originalMessage
     *            the original string
     * @param settings
     *            the settings
     * @param additionalProperties
     *            the additional values
     * @return the built message
     */
    private String substituteProperties(String originalMessage, Settings settings,
                                        Map<String, Object> additionalProperties) {
        if (additionalProperties.isEmpty()) {
            return StrSubstitutor.replace(originalMessage, settings.getProperties());
        }
        additionalProperties.putAll(settings.getProperties());
        return StrSubstitutor.replace(originalMessage, additionalProperties);
    }

    /**
     * Create the key
     *
     * @param prefix
     *            the prefix key
     * @param key
     *            the key
     * @return the key
     */
    private String prefixKey(String prefix, String key) {
        return new StringBuffer(prefix).append(ISSUE_SEPARATOR).append(key).toString();
    }

    //ReviewUtils


//    private static final Logger LOG = Loggers.get(ReviewUtils.class);
    public static final String UNKNOWN = "UNKNOWN";
    public static final int UNKNOWN_VALUE = -1;


    public int thresholdToValue(String threshold) {
        int thresholdValue = UNKNOWN_VALUE;

        try {
            thresholdValue = Severity.valueOf(threshold).ordinal();
        }
        catch (Exception e) {
            LOG.warn("[GERRIT PLUGIN] Cannot convert threshold String {} to int. Using UNKNOWN.", threshold);
            thresholdValue = UNKNOWN_VALUE;
        }

        LOG.debug("[GERRIT PLUGIN] {} is converted to {}", threshold, thresholdValue);

        return thresholdValue;
    }

    public String valueToThreshold(int value) {
        String threshold = UNKNOWN;

        try {
            threshold = Severity.values()[value].toString();
        }
        catch (Exception e){
            LOG.warn("[GERRIT PLUGIN] Cannot convert threshold int {} to String. Using UNKNOWN.", value);
        }

        LOG.debug("[GERRIT PLUGIN] {} is converted to {}", value, threshold);

        return threshold;
    }













}
