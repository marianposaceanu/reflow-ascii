(ns reflow-ascii.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [reflow-ascii.core :as diagram]
            [reflow-ascii.markup :as markup]))

(def decision
  {:nodes [{:id :start :label "Memory pressure"}
           {:id :question :label "One process over half?"}
           {:id :kill :label "Kill the process"}
           {:id :policy :label "Apply its policy"}
           {:id :dialog :label "Show the dialog"}]
   :edges [{:from :start :to :question}
           {:from :question :to :kill :label "yes"}
           {:from :question :to :policy :label "no" :primary true}
           {:from :policy :to :dialog}]})

(defn fits? [columns text]
  (every? #(<= (count %) columns) (str/split-lines text)))

(deftest responsive-layouts
  (testing "The same graph chooses a wide or stacked layout from its budget"
    (is (= :wide (:mode (diagram/layout decision {:columns 90}))))
    (is (= :stacked (:mode (diagram/layout decision {:columns 32}))))
    (is (= :references (:mode (diagram/layout decision {:columns 8}))))))

(deftest width-contract
  (doseq [columns (range 8 161)]
    (let [result (diagram/layout decision {:columns columns})]
      (is (fits? columns (:text result)) (str "column budget " columns))
      (is (<= (:width result) columns))
      (is (= result (diagram/layout decision {:columns columns}))))))

(deftest readable-branches
  (let [wide (diagram/render decision {:columns 90})
        narrow (diagram/render decision {:columns 32})]
    (is (str/includes? wide "-- yes -->"))
    (is (str/includes? wide "| no"))
    (is (str/includes? narrow " |  yes"))
    (is (str/includes? narrow " |  no"))
    (doseq [{:keys [label]} (:nodes decision)]
      (is (str/includes? (str/replace narrow #"[^A-Za-z]+" "")
                         (str/replace label #"[^A-Za-z]+" ""))))))

(deftest wrapping
  (is (= ["hello" "world"] (diagram/wrap-text "hello world" 7)))
  (is (= ["abcd" "efgh" "i"] (diagram/wrap-text "abcdefghi" 4)))
  (is (= ["a" "" "b"] (diagram/wrap-text "a\n\nb" 10)))
  (let [graph {:nodes [{:id :long :label (apply str (repeat 130 "x"))}]}]
    (doseq [columns (range 8 80)]
      (let [text (diagram/render graph {:columns columns})]
        (is (fits? columns text))
        (is (= 130 (count (filter #{\x} text))))))))

(deftest non-tree-topologies
  (doseq [graph [{:nodes [{:id :a :label "A"} {:id :b :label "B"}]
                  :edges [{:from :a :to :b} {:from :b :to :a :label "retry"}]}
                 {:nodes [{:id :a :label "A"} {:id :b :label "B"}
                          {:id :c :label "C"} {:id :d :label "D"}]
                  :edges [{:from :a :to :b} {:from :a :to :c}
                          {:from :b :to :d} {:from :c :to :d}]}
                 {:nodes [{:id :a :label "A"} {:id :b :label "B"}]}]]
    (let [result (diagram/layout graph {:columns 28})]
      (is (= :references (:mode result)))
      (is (fits? 28 (:text result)))))
  (let [loop-graph {:nodes [{:id :a :label "Retry"}]
                    :edges [{:from :a :to :a :label "again"}]}]
    (is (str/includes? (diagram/render loop-graph) "again: -> [1]"))))

(deftest deep-and-multiway-trees
  ;; Deterministic generated topologies cover chains, binary trees, and wide fans.
  (doseq [size [1 2 9 30 100]
          parent-of [#(dec %) #(quot (dec %) 2) (constantly 0)]
          columns [8 12 20 32 60 100 160]]
    (let [graph {:nodes (mapv #(hash-map :id (str %) :label (str "Node " %))
                              (range size))
                 :edges (mapv #(hash-map :from (str (parent-of %)) :to (str %)
                                        :label (str "path " %))
                              (range 1 size))}]
      (is (fits? columns (diagram/render graph {:columns columns}))))))

(deftest immutable-input
  (let [before decision]
    (diagram/render decision {:columns 32})
    (is (= before decision))))

(deftest validation
  (doseq [graph [nil {:nodes []}
                 {:nodes [{:id :a :label "A"} {:id :a :label "B"}]}
                 {:nodes [{:id :a :label "A"}] :edges [{:from :a :to :missing}]}
                 {:nodes [{:id :a :label "A"}] :root :missing}
                 {:nodes [{:id :a :label "A\tB"}]}
                 {:nodes [{:id :a :label "Unicode: \u03bb"}]}
                 (update decision :edges conj
                         {:from :question :to :kill :primary true})]]
    (is (thrown? js/Error (diagram/render graph))))
  (doseq [columns [0 7 513 10.5 js/NaN]]
    (is (thrown? js/Error (diagram/render decision {:columns columns})))))

(deftest safe-static-markup
  (let [graph {:nodes [{:id :x :label "</script><img src=x onerror=alert(1)>"}]}
        html (markup/render graph {:id "safe"})]
    (is (= 1 (count (re-seq #"</script>" html))))
    (is (not (str/includes? html "<img")))
    (is (str/includes? html "\\u003c/script\\u003e"))
    (is (str/includes? html "&lt;/script&gt;"))
    (is (str/includes? html "data-ascii-source=\"safe-model\"")))
  (is (thrown? js/Error (markup/render decision {:id "x\" onclick=\"bad"}))))

(deftest accessible-meaning
  (is (str/includes? (diagram/describe decision) "yes: Kill the process"))
  (is (= "My full description"
         (diagram/describe (assoc decision :description "My full description")))))

(deftest keyword-identity-in-exports
  (let [graph {:nodes [{:id :left/end :label "Left"}
                       {:id :right/end :label "Right"}]
               :edges [{:from :left/end :to :right/end}]}
        html (markup/render graph)]
    (is (str/includes? html "left/end"))
    (is (str/includes? html "right/end")))
  (is (thrown? js/Error
               (diagram/render {:nodes [{:id :same :label "A"}
                                        {:id "same" :label "B"}]}))))
