package com.saurabh.artifact.model;

import androidx.appsearch.annotation.Document;
import java.util.ArrayList;
import java.util.List;

/**
 * AppSearch document representing user preferences and interaction history.
 * Written in Java to ensure compatibility with annotationProcessor in this environment.
 */
@Document
public class UserPreferenceDocument {
    @Document.Namespace
    private String namespace;

    @Document.Id
    private String id;

    @Document.Score
    private int score;

    @Document.StringProperty
    private String primaryGoal;

    @Document.StringProperty
    private List<String> goals;

    @Document.StringProperty
    private String dominantEmotion;

    @Document.LongProperty
    private long lastInteractionTimestamp;

    public UserPreferenceDocument() {
        this.goals = new ArrayList<>();
        this.lastInteractionTimestamp = System.currentTimeMillis();
    }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public String getPrimaryGoal() { return primaryGoal; }
    public void setPrimaryGoal(String primaryGoal) { this.primaryGoal = primaryGoal; }

    public List<String> getGoals() { return goals; }
    public void setGoals(List<String> goals) { this.goals = goals; }

    public String getDominantEmotion() { return dominantEmotion; }
    public void setDominantEmotion(String dominantEmotion) { this.dominantEmotion = dominantEmotion; }

    public long getLastInteractionTimestamp() { return lastInteractionTimestamp; }
    public void setLastInteractionTimestamp(long lastInteractionTimestamp) { this.lastInteractionTimestamp = lastInteractionTimestamp; }
}
