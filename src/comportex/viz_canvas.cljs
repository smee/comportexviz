(ns comportex.viz-canvas
  (:require [org.nfrac.comportex.pooling :as p]
            [org.nfrac.comportex.encoders :as enc]
            [org.nfrac.comportex.util :as util]
            [goog.dom :as dom]
            [goog.dom.forms :as forms]
            goog.ui.Slider
            goog.ui.Component.EventType
            [goog.events :as events]
            [cljs.core.async :refer [put! chan <! alts! timeout]]
            [monet.canvas :as c]
            [monet.core])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; initial CLA region
(def numb-bits 64)
(def numb-on-bits 11)
(def numb-max 100)
(def numb-min 0)
(def numb-domain [numb-min numb-max])
(def n-in-items 3)
(def bit-width (* numb-bits n-in-items))
(def ncol 200)

(def r-init (p/region (assoc p/spatial-pooler-defaults
                        :ncol ncol
                        :input-size bit-width
                        :potential-radius (quot bit-width 5)
                        :global-inhibition false
                        :stimulus-threshold 2
                        :duty-cycle-period 100)))

(defn inputs-transform
  [xs]
  (mapv (fn [x]
          (mod (+ x 2) numb-max))
        xs))

(defn add-noise
  [delta xs]
  (mapv (fn [x]
          (-> (+ x (util/rand-int (- delta) (inc delta)))
              (min numb-max)
              (max numb-min)))
        xs))

(defn gen-ins
  []
  (repeatedly n-in-items #(util/rand-int numb-min numb-max)))

(def efn
  (enc/juxtapose-encoder
   (enc/linear-number-encoder numb-bits numb-on-bits numb-domain)))

(defn dense
  [is bits]
  (loop [bs (transient (vec (repeat bits false)))
         is is]
    (if (seq is)
      (recur (assoc! bs (first is) true)
             (rest is))
      (persistent! bs))))

(def input-state (atom (vec (repeat n-in-items (/ numb-max 2)))))
(def r-state (atom r-init))
(def sim-go? (atom false))
(def sim-step-ms (atom 1000))
(def animation-go? (atom false))
(def animation-step-ms (atom 1000))
(def display-options (atom {:display-active-columns true}))

;; keep recent time steps
(def keep-steps 50)
(def inbits-q (atom (vec (repeat keep-steps nil))))
(def rgn-q (atom (vec (repeat keep-steps nil))))

(defn sim-step!
  []
  (let [newin (swap! input-state inputs-transform)
        newbits (efn newin)
        newrgn (swap! r-state p/pooling-step newbits)]
    (swap! inbits-q (fn [q]
                      (conj (subvec q 1) newbits)))
    (swap! rgn-q (fn [q]
                   (conj (subvec q 1) newrgn)))))

(defn run-sim
  []
  (go
   (while @sim-go?
     (sim-step!)
     (<! (timeout @sim-step-ms)))))

;; GRAPHIC DISPLAY

(enable-console-print!)

(def width 800)
(def bitpx 6)
(def fill% 0.9)
(def inbits-height (* bitpx bit-width))
(def rgn-height (* bitpx ncol))
(def height (max inbits-height rgn-height))

(def canvas-dom (dom/getElement "viz"))
;; need to set canvas size in js not CSS, the latter delayed so
;; get-context would see the wrong resolution here.
(set! (.-width canvas-dom) width)
(set! (.-height canvas-dom) 1000)

(def canvas-ctx (c/get-context canvas-dom "2d"))

(defn overlap-frac
  [x]
  (-> (/ (- x (:stimulus-threshold @r-state))
         10) ;; arbitrary scale
      (min 0.90)
      (max 0.10)))

(defn hexit
  [z]
  (let [i (.floor js/Math (* z 16))]
    (if (< i 10)
      (str i)
      (case i
        10 "a"
        11 "b"
        12 "c"
        13 "d"
        14 "e"
        "f"))))

(defn rgbhex
  [r g b]
  (str "#" (hexit r) (hexit g) (hexit b)))

(defn greyhex
  [z]
  (rgbhex z z z))

(defn draw-inbits
  [ctx data t]
  (c/save ctx)
  (c/scale ctx bitpx bitpx)
  (c/stroke-width ctx 0.05)
  (c/stroke-style ctx "#000")
  (doseq [dt (range (count data))
          :let [bits (data dt)]]
    (c/alpha ctx (/ dt (* keep-steps 1.2)))
    (c/clear-rect ctx {:x dt :y 0 :w 1 :h bit-width})
    (c/fill-style ctx "#f00")
    (doseq [b (range bit-width)]
      (c/stroke-rect ctx {:x dt :y b :w fill% :h fill%}))
    (doseq [b bits]
      (c/fill-rect ctx {:x dt :y b :w fill% :h fill%})
      (c/stroke-rect ctx {:x dt :y b :w fill% :h fill%})))
  (c/restore ctx)
  ctx)

(defn rgn-column-states
  [rgn]
  (let [ac (:active-columns rgn)
        om (:overlaps rgn)
        am (zipmap ac (repeat :active))]
    (merge om am)))

(defn draw-rgn
  [ctx data t]
  (c/save ctx)
  (c/translate ctx (* keep-steps bitpx 1.5) 0)
  (c/scale ctx bitpx bitpx)
  (c/stroke-width ctx 0.05)
  (c/stroke-style ctx "#000")
  (doseq [dt (range (count data))
          :let [m (data dt)]]
    (c/alpha ctx (/ dt (* keep-steps 1.2)))
    (c/clear-rect ctx {:x dt :y 0 :w 1 :h ncol})
    (c/fill-style ctx "#fff")
    (doseq [cid (range ncol)]
      (c/circle ctx {:x (+ 0.5 dt) :y (+ 0.5 cid) :r (* fill% 0.5)})
      (c/stroke ctx))
    (doseq [[cid cval] m]
      (let [of (overlap-frac cval)
            color (case cval
                    :inactive "#fff"
                    :active "#f00"
                    (greyhex (- 1 of)))]
        (c/fill-style ctx color)
        (c/circle ctx {:x (+ 0.5 dt) :y (+ 0.5 cid) :r (* fill% 0.5)})
        (c/stroke ctx))))
  (c/restore ctx)
  ctx)

(defn draw-insynapses
  [data]
  )

(defn detail-text
  []
  (let [newin @input-state
        newbits (efn newin)
        newr @r-state
        newom (:overlaps newr)
        newac (:active-columns newr)]
    (apply str
           (interpose \newline
                      ["# Input"
                       newin
                       "# Input bits"
                       (sort newbits)
                       "# Active columns"
                       (sort newac)
                       "# Overlaps map"
                       (sort newom)]))))

;; use core.async to run simulation separately from animation

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type
                   (fn [e] (put! out e)))
    out))

(defn handle-sim-ms
  [s]
  (let [changes (listen s goog.ui.Component.EventType/CHANGE)
        txt (dom/getElement "sim-ms-text")]
    (go (while true
          (let [e (<! changes)
                newval (reset! sim-step-ms (.getValue s))]
            (set! (.-innerHTML txt) newval))))))

(defn handle-animation-ms
  [s]
  (let [changes (listen s goog.ui.Component.EventType/CHANGE)
        txt (dom/getElement "animation-ms-text")]
    (go (while true
          (let [e (<! changes)
                newval (reset! animation-step-ms (.getValue s))]
            (set! (.-innerHTML txt) newval))))))

(defn init-ui!
  []
  (let [s-sim (doto (goog.ui.Slider.)
                (.setId "sim-ms-slider")
                (.setMaximum 2000)
                (.createDom)
                (.render (dom/getElement "sim-ms-slider-box")))
        s-anim (doto (goog.ui.Slider.)
                 (.setId "animation-ms-slider")
                 (.setMaximum 2000)
                 (.createDom)
                 (.render (dom/getElement "animation-ms-slider-box")))]
    (handle-sim-ms s-sim)
    (handle-animation-ms s-anim)
    (.setValue s-sim @sim-step-ms)
    (.setValue s-anim @animation-step-ms)))

(defn handle-sim-control
  []
  (let [btn (dom/getElement "sim-control")
        clicks (listen btn "click")]
    (go (while true
          (let [e (<! clicks)
                newval (swap! sim-go? not)]
            (set! (.-innerHTML (.-currentTarget e))
                  (if newval "Stop" "Start"))
            (when newval (run-sim)))))))

(defn handle-sim-step
  []
  (let [btn (dom/getElement "sim-step")
        clicks (listen btn "click")]
    (go (while true
          (<! clicks)
          (sim-step!)))))

(defn update-text-display
  []
  (let [ts-el (dom/getElement "sim-timestep")
        info-el (dom/getElement "detail-text")]
    (set! (.-innerHTML ts-el) (:timestep @r-state))
    (forms/setValue info-el (detail-text))))

(defn animation-step!
  []
  (let [newr @r-state
        t (:timestep newr)
        newbits (efn @input-state)
        o @display-options]
    (update-text-display)
    (draw-inbits canvas-ctx @inbits-q t)
    (let [rgn-data (mapv rgn-column-states @rgn-q)]
      (draw-rgn canvas-ctx rgn-data t)
      (let [ac (:active-columns newr)
            syn-cols (select-keys (:columns newr) ac)
            syn-data (->> syn-cols
                          (mapcat (fn [[col-id col]]
                                    (let [syns (cond-> {}
                                                       (:display-connected-insyns o)
                                                       (merge (-> col :in-synapses :connected))
                                                       (:display-disconnected-insyns o)
                                                       (merge (-> col :in-synapses :disconnected))
                                                       true
                                                       (select-keys newbits))]
                                      (map (fn [[in-id perm]]
                                             [[in-id col-id] perm])
                                           syns)))))]
        (draw-insynapses syn-data)))))

(defn run-animation
  []
  (go
   (while @animation-go?
     (animation-step!)
     (<! (timeout @animation-step-ms)))))

(defn handle-animation-control
  []
  (let [btn (dom/getElement "animation-control")
        clicks (listen btn "click")]
    (go (while true
          (let [e (<! clicks)
                newval (swap! animation-go? not)]
            (set! (.-innerHTML (.-currentTarget e))
                  (if newval "Stop" "Start"))
            (when newval (run-animation)))))))

(defn handle-animation-step
  []
  (let [btn (dom/getElement "animation-step")
        clicks (listen btn "click")]
    (go (while true
          (<! clicks)
          (animation-step!)))))

(defn handle-display-options
  []
  (let [ids ["display-connected-insyns"
             "display-disconnected-insyns"
             "display-active-columns"
             "display-overlap-columns"]
        btns (map dom/getElement ids)
        cs (map listen btns (repeat "click"))
        cm (zipmap cs ids)]
    (doseq [[el id] (map vector btns ids)]
      (forms/setValue el (get @display-options (keyword id))))
    (go (while true
          (let [[e c] (alts! (keys cm))
                id (cm c)
                on? (forms/getValue (.-currentTarget e))]
            (swap! display-options assoc (keyword id) on?))))))

(init-ui!)
(handle-sim-control)
(handle-sim-step)
(handle-animation-control)
(handle-animation-step)
(handle-display-options)
