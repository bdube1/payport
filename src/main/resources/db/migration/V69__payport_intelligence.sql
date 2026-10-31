-- PayPort Intelligence, first features (owner agreed 31 Oct 2026): read-only helpers behind switches, all off.
-- They run on PayPort's own servers and data; nothing is sent to an outside AI provider. A person always decides.
CREATE TABLE ai_features (
    feature     VARCHAR(40)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by  VARCHAR(160) NULL,
    updated_at  DATETIME(6)  NULL
);
INSERT INTO ai_features (feature, enabled) VALUES ('COMPLAINT_TRIAGE', FALSE), ('INVESTIGATION_HELPER', FALSE), ('FRAUD_SHADOW', FALSE);

-- Each trained model: when, on how much data, how well it did on data it had not seen.
CREATE TABLE ai_models (
    feature     VARCHAR(40)  NOT NULL,
    version     INT          NOT NULL,
    trained_at  DATETIME(6)  NOT NULL,
    samples     INT          NOT NULL,
    metrics     JSON         NOT NULL,
    PRIMARY KEY (feature, version)
);

-- What a model said about something, and later what turned out to be true (how it is measured).
CREATE TABLE ai_predictions (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature       VARCHAR(40)  NOT NULL,
    subject_ref   VARCHAR(100) NOT NULL,
    model_version INT          NOT NULL,
    label         VARCHAR(60)  NOT NULL,
    score         DOUBLE       NOT NULL,
    detail        JSON         NULL,
    created_at    DATETIME(6)  NOT NULL,
    outcome       VARCHAR(60)  NULL,
    outcome_at    DATETIME(6)  NULL,
    CONSTRAINT uq_ai_prediction UNIQUE (feature, subject_ref),
    INDEX idx_ai_predictions (feature, created_at),
    INDEX idx_ai_predictions_label (feature, label, created_at)
);

-- Where shadow scoring is in the platform event stream.
CREATE TABLE ai_cursor (
    name     VARCHAR(40) PRIMARY KEY,
    last_id  BIGINT      NOT NULL
);
INSERT INTO ai_cursor (name, last_id) VALUES ('FRAUD_SHADOW', 0);
