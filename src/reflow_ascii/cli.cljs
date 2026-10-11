(ns reflow-ascii.cli
  "Render an EDN or JSON model to ASCII or progressively enhanced markup."
  (:require ["node:fs" :as fs]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [reflow-ascii.core :as diagram]
            [reflow-ascii.markup :as markup]))

(def ^:private usage
  (str "Usage: reflow-ascii graph.edn|graph.json [--columns 80]"
       " [--html] [--id diagram-id]\n"
       "Writes ASCII, or a static pre and inert graph JSON with --html."))

(defn- parse-options [arguments]
  (loop [[flag value & more :as remaining] arguments options {}]
    (case flag
      nil options
      "--html" (recur (next remaining) (assoc options :html? true))
      "--columns" (recur more (assoc options :columns (js/Number value)))
      "--id" (if value
               (recur more (assoc options :id value))
               (throw (ex-info "--id needs a value" {})))
      (throw (ex-info (str "Unknown option: " flag) {})))))

(defn- read-model [file]
  (let [text (fs/readFileSync file "utf8")]
    (if (str/ends-with? file ".edn")
      (edn/read-string text)
      (js->clj (js/JSON.parse text) :keywordize-keys true))))

(defn main [& arguments]
  (if (or (empty? arguments) (some #{"--help"} arguments))
    (do (println usage)
        (when (empty? arguments) (set! (.-exitCode js/process) 1)))
    (try
      (let [[file & flags] arguments
            options (parse-options flags)
            graph (read-model file)]
        (println ((if (:html? options) markup/render diagram/render)
                  graph options)))
      (catch :default error
        (js/console.error (.-message error))
        (set! (.-exitCode js/process) 1)))))
