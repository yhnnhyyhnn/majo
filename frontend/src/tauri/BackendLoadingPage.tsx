import { useEffect, useState } from "react";
import { type CSSProperties } from "react";
import { useTheme } from "../contexts/ThemeContext";
import { useTranslation } from "react-i18next";
import styles from "./BackendLoadingPage.module.less";
import { type BackendReadyStatus } from "./useBackendReadyPolling";

const BRAND_COLOR = "#ff7f16";
const ERROR_COLOR = "#ff4d4f";
/** How often (ms) the loading tip rotates. */
const TIP_ROTATE_INTERVAL_MS = 5000;

interface BackendLoadingPageProps {
  status: BackendReadyStatus;
  elapsed: number;
  errorMessage?: string;
  onRetry?: () => void;
}

export default function BackendLoadingPage({
  status,
  elapsed,
  errorMessage,
  onRetry,
}: BackendLoadingPageProps) {
  const { isDark } = useTheme();
  const { t } = useTranslation();

  // Randomly pick an AI-related fun fact to show while waiting.
  const tips = (t("startup.tips", { returnObjects: true }) as unknown) as
    | string[]
    | undefined;
  const tipCount = Array.isArray(tips) ? tips.length : 0;
  const [tipIndex, setTipIndex] = useState(() =>
    Math.floor(Math.random() * Math.max(tipCount, 1)),
  );
  useEffect(() => {
    if (tipCount <= 1) return undefined;
    const id = setInterval(
      () => setTipIndex((i) => (i + 1) % tipCount),
      TIP_ROTATE_INTERVAL_MS,
    );
    return () => clearInterval(id);
  }, [tipCount]);
  const hasFailed = status === "timeout" || status === "error";
  const statusText =
    status === "error"
      ? t("startup.error", "Backend failed to start.")
      : status === "timeout"
      ? t("startup.timeout", {
          seconds: elapsed,
          defaultValue: "Backend failed to start within {{seconds}} seconds.",
        })
      : elapsed === 0
      ? t("startup.starting", "Starting backend...")
      : t("startup.checking", "Connecting to backend...");

  const style = {
    "--majo-brand-color": BRAND_COLOR,
    "--majo-error-color": ERROR_COLOR,
  } as CSSProperties;

  return (
    <div
      className={`${styles.page} ${
        isDark ? styles.pageDark : styles.pageLight
      }`}
      style={style}
    >
      <span className={`${styles.ambient} ${styles.ambientA}`} aria-hidden="true" />
      <span className={`${styles.ambient} ${styles.ambientB}`} aria-hidden="true" />
      <div className={styles.card}>
        <img src="/majo-icon.svg" alt="Majo" className={styles.logo} />

        <div className={styles.barWrap}>
          <div
            className={`${styles.barTrack} ${hasFailed ? styles.failed : ""}`}
            role="progressbar"
            aria-label={hasFailed ? statusText : "loading"}
          >
            <div className={styles.barFill} />
          </div>
          <div className={styles.progressLabel}>{`${elapsed}s`}</div>
        </div>

        {!hasFailed && tipCount > 0 && (
          <p key={`tip-${tipIndex}`} className={styles.tip}>
            {tips![tipIndex % tipCount]}
          </p>
        )}

        {hasFailed && (
          <p
            className={`${styles.statusText} ${styles.failedText}`}
          >
            {statusText}
          </p>
        )}

        {hasFailed && (
          <>
            <p className={styles.hint}>
              {status === "error"
                ? t(
                    "startup.errorHint",
                    "The backend process could not be launched. Check application logs for details.",
                  )
                : t(
                    "startup.timeoutHint",
                    "Backend failed to start. Please retry, or check application logs for details.",
                  )}
            </p>
            {errorMessage && (
              <details className={styles.details}>
                <summary className={styles.summary}>
                  {t("startup.errorDetails", "Show error details")}
                </summary>
                <pre className={styles.errorDetails}>{errorMessage}</pre>
              </details>
            )}
            <button
              className={styles.retryButton}
              onClick={onRetry}
              type="button"
            >
              {t("startup.retry", "Retry")}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
