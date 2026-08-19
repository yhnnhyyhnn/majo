import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import mermaid from "mermaid";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark } from "react-syntax-highlighter/dist/esm/styles/prism";
import styles from "./index.module.less";

let mermaidInitialized = false;
let idCounter = 0;

function ensureMermaidInit() {
  if (mermaidInitialized) return;
  mermaid.initialize({
    startOnLoad: false,
    theme: "neutral",
    securityLevel: "loose",
  });
  mermaidInitialized = true;
}

function MermaidDiagram({ chart }: { chart: string }) {
  const trimmedChart = chart.trim();
  const [svg, setSvg] = useState<string>("");
  const [error, setError] = useState<string>("");
  const [isRendering, setIsRendering] = useState<boolean>(!!trimmedChart);

  useEffect(() => {
    if (!trimmedChart) {
      setSvg("");
      setError("");
      setIsRendering(false);
      return;
    }

    ensureMermaidInit();

    let cancelled = false;
    const id = `mermaid-${Date.now()}-${idCounter++}`;
    setSvg("");
    setError("");
    setIsRendering(true);

    mermaid
      .render(id, trimmedChart)
      .then(({ svg: rendered }) => {
        if (!cancelled) {
          setSvg(rendered);
          setError("");
          setIsRendering(false);
        }
      })
      .catch((renderError) => {
        if (!cancelled) {
          setError(String(renderError));
          setSvg("");
          setIsRendering(false);
        }
        const orphan = document.getElementById("d" + id);
        orphan?.remove();
      });

    return () => {
      cancelled = true;
    };
  }, [trimmedChart]);

  if (error) {
    return (
      <pre className={styles.mermaidError}>
        <code>{chart}</code>
      </pre>
    );
  }

  return (
    <div
      className={`${styles.mermaidDiagram}${
        isRendering ? ` ${styles.isLoading}` : ""
      }`}
    >
      {isRendering ? (
        <div className={styles.placeholder} aria-hidden="true">
          Loading diagram…
        </div>
      ) : null}
      {svg ? (
        <div
          className={styles.content}
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      ) : null}
    </div>
  );
}

export function MermaidCodeBlock({ chart }: { chart: string }) {
  return <MermaidDiagram chart={chart} />;
}

interface MermaidBlockProps {
  chart: string;
  defaultView?: "preview" | "source";
}

/**
 * Mermaid code block with Preview / Source tabs. Renders the diagram by
 * default and lets the user switch to the raw mermaid source — mirrors
 * qwenpaw's RenderableCodeBlock behaviour for mermaid blocks.
 */
export function MermaidToggleBlock({
  chart,
  defaultView = "preview",
}: MermaidBlockProps) {
  const { t } = useTranslation();
  const [view, setView] = useState<"preview" | "source">(defaultView);
  return (
    <div className={styles.toggleBlock}>
      <div className={styles.toggleTabs} role="tablist" aria-label="mermaid">
        <button
          type="button"
          role="tab"
          aria-selected={view === "preview"}
          className={view === "preview" ? styles.toggleTabActive : styles.toggleTab}
          onClick={() => setView("preview")}
        >
          {t("common.preview")}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={view === "source"}
          className={view === "source" ? styles.toggleTabActive : styles.toggleTab}
          onClick={() => setView("source")}
        >
          {t("common.source")}
        </button>
      </div>
      {view === "preview" ? (
        <MermaidDiagram chart={chart} />
      ) : (
        <div className={styles.sourceBlock}>
          <SyntaxHighlighter
            language="mermaid"
            style={oneDark}
            customStyle={{
              margin: 0,
              borderRadius: "6px",
              fontSize: "13px",
              lineHeight: "1.6",
            }}
          >
            {chart.replace(/\n$/, "")}
          </SyntaxHighlighter>
        </div>
      )}
    </div>
  );
}
