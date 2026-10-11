(ns reflow-ascii.core
  "Pure graph validation and responsive ASCII layout. No browser state."
  (:require [clojure.string :as str]))

(def min-columns 8)
(def max-columns 512)

(defn keyword-text
  "Preserve namespaces when a keyword crosses the JSON boundary."
  [value]
  (subs (str value) 1))

(defn- ensure! [pred message data]
  (when-not pred (throw (ex-info (str "reflow-ascii: " message) data))))

(defn- clean-label [text context required?]
  (ensure! (string? text) "labels must be strings" {:context context})
  (ensure! (re-matches #"[\x20-\x7e\n\r]*" text)
           "labels must contain printable ASCII and line breaks only" {:context context})
  (let [text (-> text (str/replace #"\r\n?" "\n") str/trim)]
    (ensure! (or (not required?) (seq text)) "labels must not be empty" {:context context})
    (ensure! (<= (count text) 2000) "label exceeds 2000 characters" {:context context})
    text))

(defn- normalize-node [{:keys [id label kind]}]
  (ensure! (and (or (keyword? id) (string? id))
                (<= 1 (count (str id)) 128))
           "invalid node id" {:id id})
  (ensure! (contains? #{nil :box :text "box" "text"} kind)
           "kind must be box or text" {:id id})
  {:id id
   :label (clean-label label id true)
   :kind (keyword (or kind :box))})

(defn- normalize-edge [by-id {:keys [from to label primary] :as edge}]
  (ensure! (and (contains? by-id from) (contains? by-id to))
           "edge has an unknown endpoint" {:edge edge})
  (ensure! (or (nil? primary) (boolean? primary))
           "primary must be boolean" {:edge edge})
  {:from from
   :to to
   :label (clean-label (or label "") edge false)
   :primary (true? primary)})

(defn- reachable-from [outgoing root]
  (loop [pending [root] seen #{}]
    (if-let [id (peek pending)]
      (if (contains? seen id)
        (recur (pop pending) seen)
        (recur (into (pop pending) (map :to (outgoing id))) (conj seen id)))
      seen)))

(defn- tree? [nodes outgoing incoming root]
  (and (= (count nodes) (count (reachable-from outgoing root)))
       (zero? (get incoming root 0))
       (every? #(or (= root (:id %)) (= 1 (get incoming (:id %)))) nodes)))

(defn normalize
  "Validate a graph and build immutable lookup tables.
   IDs may be strings or keywords. Input order determines branch order."
  [{:keys [nodes edges root title description] :as graph}]
  (ensure! (map? graph) "a graph map is required" {})
  (ensure! (and (sequential? nodes) (<= 1 (count nodes) 100))
           "nodes must contain 1 to 100 items" {})
  (ensure! (or (nil? title) (string? title)) "title must be a string" {})
  (ensure! (or (nil? description) (string? description))
           "description must be a string" {})
  (ensure! (and (or (nil? edges) (sequential? edges)) (<= (count edges) 300))
           "edges must be a sequence of at most 300 items" {})
  (let [nodes (mapv normalize-node nodes)
        by-id (into {} (map (juxt :id identity)) nodes)
        edges (mapv #(normalize-edge by-id %) edges)
        outgoing (merge (zipmap (keys by-id) (repeat []))
                        (group-by :from edges))
        incoming (frequencies (map :to edges))
        root (or root
                 (:id (first (remove #(contains? incoming (:id %)) nodes)))
                 (:id (first nodes)))]
    (ensure! (= (count nodes) (count by-id)) "node ids must be unique" {})
    (ensure! (= (count nodes)
                (count (set (map #(let [id (:id %)]
                                   (if (keyword? id) (keyword-text id) id))
                                 nodes))))
             "node ids must remain unique when serialized" {})
    (ensure! (contains? by-id root) "root must name an existing node" {:root root})
    (doseq [[id routes] outgoing]
      (ensure! (<= (count (filter :primary routes)) 1)
               "only one primary edge is allowed per node" {:id id}))
    {:nodes nodes
     :edges edges
     :root root
     :by-id by-id
     :outgoing outgoing
     :tree? (tree? nodes outgoing incoming root)
     :title title
     :description description}))

(defn- spaces [n] (apply str (repeat n \space)))
(defn- dashes [n] (apply str (repeat n \-)))
(defn- pad-right [s width] (str s (spaces (- width (count s)))))

(defn wrap-text
  "Word wrap at a cell budget, splitting long tokens and preserving hard breaks."
  [text width]
  (vec
   (mapcat
    (fn [paragraph]
      (loop [words (seq (re-seq #"[^ ]+" (str/trim paragraph))) line "" lines []]
        (if-let [word (first words)]
          (cond
            (and (seq line) (> (+ (count line) 1 (count word)) width))
            (recur words "" (conj lines line))

            (seq line)
            (recur (next words) (str line " " word) lines)

            (> (count word) width)
            (recur (cons (subs word width) (next words)) "" (conj lines (subs word 0 width)))

            :else (recur (next words) word lines))
          (conj lines line))))
    (str/split text #"\n" -1))))

(defn- node-block [{:keys [label kind]} width]
  (let [lines (if (= kind :text)
                (mapv #(pad-right (str (spaces (quot (- width (count %)) 2)) %) width)
                      (wrap-text label width))
                (let [border (str "+" (dashes (- width 2)) "+")]
                  (into [border]
                        (concat (map #(str "| " (pad-right % (- width 4)) " |")
                                     (wrap-text label (- width 4))) [border]))))]
    {:lines lines :width width :anchor (quot (dec (count lines)) 2)}))

;; Canvas operations return new vectors; the layout engine has no mutable state.
(defn- put-text [rows x y text]
  (let [row (nth rows y)]
    (ensure! (<= 0 x (+ x (count text)) (count row)) "layout exceeded its canvas" {:x x :y y})
    (assoc rows y (str (subs row 0 x) text (subs row (+ x (count text)))))))

(defn- put-block [rows x y {:keys [lines]}]
  (reduce-kv #(put-text %1 x (+ y %2) %3) rows lines))

(defn- down-link [{:keys [label]} width]
  (let [center (quot width 2)
        stem (str (spaces center) "|")]
    (conj (if (seq label)
            (mapv #(str stem " " %) (wrap-text label (- width center 2)))
            [stem])
          (str (spaces center) "v"))))

(defn- wide-tree [{:keys [by-id outgoing] :as graph} id width budget]
  (let [own (node-block (by-id id) width)
        children (outgoing id)]
    (cond
      (empty? children) own
      (> (count children) 2) nil
      :else
      (let [main (or (first (filter :primary children)) (last children))
            side (first (remove #(= main %) children))
            below (wide-tree graph (:to main) width budget)
            beside (when side (wide-tree graph (:to side) width budget))
            bridge (when side
                     (if (seq (:label side))
                       (str "-- " (:label side) " -->")
                       "---->"))
            branch-width (if side
                           (+ width (count bridge) (or (:width beside) 0))
                           width)
            total-width (max (or (:width below) 0) branch-width)]
        (when (and below
                   (or (nil? side)
                       (and beside (not (str/includes? (:label side) "\n"))))
                   (<= total-width budget))
          (let [own-y (if beside (max 0 (- (:anchor beside) (:anchor own))) 0)
                beside-y (if beside (max 0 (- (:anchor own) (:anchor beside))) 0)
                top-height (max (+ own-y (count (:lines own)))
                                (+ beside-y (count (:lines beside))))
                link (down-link main width)
                below-y (+ top-height (count link))
                rows (vec (repeat (+ below-y (count (:lines below))) (spaces total-width)))
                rows (put-block rows 0 own-y own)
                rows (if side
                       (-> rows
                           (put-block (+ width (count bridge)) beside-y beside)
                           (put-text width (+ own-y (:anchor own)) bridge)) rows)
                rows (reduce #(put-text %1 (quot width 2) %2 "|") rows
                             (range (+ own-y (count (:lines own))) top-height))
                rows (-> rows (put-block 0 top-height {:lines link}) (put-block 0 below-y below))]
            {:lines (mapv str/trimr rows) :width total-width :anchor (+ own-y (:anchor own))}))))))

(defn- fork-lines [edge child width last?]
  (let [center (+ 4 (quot (- width 4) 2))
        rail (if last? "    " " |  ")]
    (concat [" |"]
            (when (seq (:label edge))
              (map #(str " |  " %) (wrap-text (:label edge) (- width 4))))
            [(str " +" (dashes (- center 2)) "+")
             (str (if last? "  " " |") (spaces (- center 2)) "v")]
            (map #(str rail %) (:lines child)))))

(defn- stacked-tree [{:keys [by-id outgoing] :as graph} id width]
  (when (>= width 12)
    (let [own (node-block (by-id id) width)
          children (outgoing id)]
      (case (count children)
        0 own
        1 (let [edge (first children)]
            (when-let [below (stacked-tree graph (:to edge) width)]
              (update own :lines into
                      (concat (down-link edge width) (:lines below)))))
        (let [nested (mapv #(stacked-tree graph (:to %) (- width 4)) children)
              center (quot width 2)
              stem [(str (spaces center) "|")
                    (str " +" (dashes (- center 2)) "+")]]
          (when (every? some? nested)
            (update own :lines into
                    (concat stem
                            (mapcat #(fork-lines %2 %3 width
                                                 (= %1 (dec (count children))))
                                    (range) children nested)))))))))

(defn- numbered-block [numbers node columns]
  (node-block (assoc node
                     :kind :box
                     :label (str "[" (numbers (:id node)) "] " (:label node)))
              columns))

(defn- destination-lines [numbers columns {:keys [to label]}]
  (wrap-text (str (when (seq label) (str label ": "))
                  "-> [" (numbers to) "]")
             columns))

(defn- reference-layout [{:keys [nodes outgoing]} columns]
  (let [numbers (zipmap (map :id nodes) (range 1 (inc (count nodes))))
        section (fn [index node]
                  (concat (when (pos? index) [""])
                          (:lines (numbered-block numbers node columns))
                          (mapcat #(destination-lines numbers columns %)
                                  (outgoing (:id node)))))]
    {:lines (vec (mapcat section (range) nodes))
     :width columns}))

(defn layout-normalized
  "Internal: layout a validated graph. Public callers normally use layout."
  [graph {:keys [columns mode] :or {columns 80 mode :auto}}]
  (ensure! (and (integer? columns) (<= min-columns columns max-columns))
           "columns must be an integer from 8 to 512" {:columns columns})
  (ensure! (contains? #{:auto :stacked :references} mode) "unknown layout mode" {:mode mode})
  (let [wide (when (and (:tree? graph) (= mode :auto))
               (some #(wide-tree graph (:root graph) % columns) (range (min columns 42) 23 -1)))
        stacked (when (and (:tree? graph) (not= mode :references) (nil? wide))
                  (stacked-tree graph (:root graph) (min columns 48)))
        block (or wide stacked (reference-layout graph (min columns 60)))
        lines (mapv str/trimr (:lines block))]
    {:text (str/join "\n" lines)
     :mode (cond wide :wide stacked :stacked :else :references)
     :columns columns :width (reduce max 0 (map count lines)) :height (count lines)}))

(defn layout
  "Return {:text :mode :columns :width :height}. Every line fits :columns."
  ([graph] (layout graph {}))
  ([graph options] (layout-normalized (normalize graph) options)))

(defn render
  "Render a graph as an ASCII string, without needing JavaScript or a DOM at read time."
  ([graph] (render graph {}))
  ([graph options] (:text (layout graph options))))

(defn- describe-route [by-id {:keys [to label]}]
  (str (if (seq label) (str label ": ") "then ")
       (str/replace (:label (by-id to)) "\n" " ")))

(defn- describe-node [outgoing by-id {:keys [id label]}]
  (let [routes (map #(describe-route by-id %) (outgoing id))]
    (str (str/replace label "\n" " ") "."
         (when (seq routes) (str " " (str/join "; " routes) ".")))))

(defn describe-normalized [{:keys [title description nodes outgoing by-id]}]
  (or (not-empty description)
      (str/join " "
                (concat (when (seq title) [title])
                        (map #(describe-node outgoing by-id %) nodes)))))

(defn describe [graph] (describe-normalized (normalize graph)))

(defn escape-html [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))
