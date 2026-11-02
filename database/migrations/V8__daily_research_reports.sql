-- 研究日报仅追加版本；不迁移、不覆盖任何旧预测或账户记录。
CREATE TABLE daily_research_report (
    id uuid PRIMARY KEY,
    report_date date NOT NULL,
    version integer NOT NULL,
    input_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    report jsonb NOT NULL
);
CREATE INDEX daily_research_report_date ON daily_research_report(report_date, version);
CREATE TRIGGER daily_research_report_immutable BEFORE UPDATE OR DELETE ON daily_research_report
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
