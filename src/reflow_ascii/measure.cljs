(ns reflow-ascii.measure
  "Browser measurements; character budgets stay out of the layout engine."
  (:require [goog.object :as object]))

(def ^:private font-properties
  ["fontFamily" "fontSize" "fontStyle" "fontWeight" "fontStretch"
   "letterSpacing" "fontFeatureSettings" "fontVariationSettings"
   "fontVariantLigatures" "fontKerning"])

(defn- font-style [^js window element]
  (let [computed (.getComputedStyle window element)]
    (into {} (map (fn [property] [property (object/get computed property)]))
          font-properties)))

(defn- cell-width! [^js document font]
  (let [probe (.createElement document "span")
        style (.-style probe)]
    (set! (.-textContent probe) (apply str (repeat 100 "M")))
    (doseq [[property value]
            (merge {"position" "absolute" "visibility" "hidden"
                    "whiteSpace" "pre" "padding" "0" "border" "0"
                    "margin" "0" "width" "auto" "maxWidth" "none"
                    "transform" "none"}
                   font)]
      (object/set style property value))
    (.append (or (.-body document) (.-documentElement document)) probe)
    (try
      (/ (.-width (.getBoundingClientRect probe)) 100)
      (finally (.remove probe)))))

(defn- pixels [value]
  (let [n (js/parseFloat value)]
    (if (js/Number.isFinite n) n 0)))

(defn measure!
  "Return a column budget and reusable font metrics for a pre's content box."
  [^js pre target previous]
  (let [document (.-ownerDocument pre)
        window (.-defaultView document)
        font (font-style window target)
        cell-width (if (= font (:font previous))
                     (:cell-width previous)
                     (cell-width! document font))
        style (.getComputedStyle window pre)
        width (- (.-clientWidth pre)
                 (pixels (.-paddingLeft style))
                 (pixels (.-paddingRight style)))
        ;; One pixel protects against fractional character and clientWidth rounding.
        columns (if (pos? cell-width)
                  (js/Math.floor (/ (dec width) cell-width))
                  0)]
    {:columns columns :font font :cell-width cell-width}))
