(ns tech.compute.tensor
  "Functions for dealing with tensors with the compute system"
  (:require [tech.compute.driver :as drv]
            [tech.compute.context :as compute-ctx]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.tensor.impl :as dtt-impl]
            [tech.v2.tensor.dimensions :as dtt-dims]
            [tech.v2.tensor :as dtt])
  (:import [tech.v2.tensor.impl Tensor]))


(defn new-tensor
  ([shape options]
   (let [{:keys [device]} (compute-ctx/options->context options)
         datatype (dtt-impl/default-datatype (:datatype options))
         ecount (long (apply * shape))
         dev-buf (drv/allocate-device-buffer device ecount datatype options)]
     (dtt-impl/construct-tensor dev-buf (dtt-dims/dimensions shape))))
  ([shape]
   (new-tensor shape {})))


(defn new-host-tensor
  ([shape options]
   (let [{:keys [driver]} (compute-ctx/options->context options)
         datatype (dtt-impl/default-datatype (:datatype options))
         ecount (long (apply * shape))
         host-buf (drv/allocate-host-buffer driver ecount datatype options)]
     (dtt-impl/construct-tensor host-buf (dtt-dims/dimensions shape))))
  ([shape]
   (new-host-tensor shape {})))


(defn clone-to-device
  "Clone a host tensor to a device.  Tensor must have relatively straighforward
  dimensions (transpose OK but arbitrary reorder or offset not OK) or :force?
  must be specified.
  options:
  :force?  Copy tensor to another buffer if necessary.
  :sync? Sync with stream to ensure copy operation is finished before moving forward."
  ([input-tens options]
   (let [input-tens (dtt/ensure-tensor input-tens)
         input-tens-buf (dtt/tensor->buffer input-tens)
         {:keys [device stream]} (compute-ctx/options->context options)
         datatype (dtype/get-datatype input-tens-buf)
         input-tens-buf (if (drv/acceptable-device-buffer? device input-tens-buf)
                          input-tens-buf
                          (dtype/make-container :native-buffer
                                                datatype
                                                input-tens-buf
                                                {:unchecked? true}))
         n-elems (dtype/ecount input-tens-buf)
         dev-buf (drv/allocate-device-buffer device n-elems datatype options)]
     (drv/copy-host->device stream
                            input-tens-buf 0
                            dev-buf 0
                            n-elems)
     (when (:sync? options)
       (drv/sync-with-host stream))
     (dtt-impl/construct-tensor dev-buf (dtt/tensor->dimensions input-tens))))
  ([input-tens]
   (clone-to-device input-tens {})))


(defn ensure-device
  "Ensure a tensor can be used on a device.  Some devices can use CPU tensors."
  ([input-tens options]
   (let [device (or (:device options)
                    (compute-ctx/default-device))]
     (if (and (drv/acceptable-device-buffer? device input-tens)
              (dtt-impl/dims-suitable-for-desc? (dtt-impl/tensor->dimensions
                                                 input-tens)))
       input-tens
       (clone-to-device input-tens))))
  ([input-tens]
   (ensure-device input-tens {})))


(defn ->tensor
  [data & {:keys [datatype device stream sync?]
           :as options}]
  (-> (dtt/->tensor data
                    :container-type :native-buffer
                    :datatype datatype)
      (ensure-device options)))


(defn clone-to-host
  "Copy this tensor to the host.  Synchronized by default."
  ([device-tens options]
   (let [options (update options
                         :sync?
                         #(if (nil? %) true %))
         driver (drv/get-driver device-tens)
         {:keys [stream]} (compute-ctx/options->context options)
         dev-buf (dtt/tensor->buffer device-tens)
         buf-elems (dtype/ecount dev-buf)
         host-buf (drv/allocate-host-buffer driver buf-elems
                                            (dtype/get-datatype dev-buf) options)]
     (drv/copy-device->host stream
                            dev-buf 0
                            host-buf 0
                            buf-elems)
     (when (:sync? options)
       (drv/sync-with-host stream))
     (dtt-impl/construct-tensor host-buf (dtt/tensor->dimensions device-tens))))
  ([device-tens]
   (clone-to-host device-tens {:sync? true})))


(defn ensure-host
  "Ensure this tensor is a 'host' tensor.  Synchronized by default."
  ([device-tens options]
   (let [driver (drv/get-driver device-tens)]
     (if (drv/acceptable-host-buffer? driver device-tens)
       device-tens
       (clone-to-host device-tens options))))
  ([device-tens]
   (ensure-host device-tens {})))


(defn ->array
  [tens & [datatype]]
  (let [datatype (or datatype (dtype/get-datatype tens))
        tens (ensure-host tens)]
    (case datatype
      :int8 (dtype/->byte-array tens)
      :int16 (dtype/->short-array tens)
      :int32 (dtype/->int-array tens)
      :int64 (dtype/->long-array tens)
      :float32 (dtype/->float-array tens)
      :float64 (dtype/->double-array tens))))


(defn ->float-array
  [tens]
  (->array tens :float32))


(defn ->double-array
  [tens]
  (->array tens :float64))


(defn rows
  [tens]
  (dtt/rows tens))


(defn columns
  [tens]
  (dtt/columns tens))


(extend-type Tensor
  drv/PDriverProvider
  (get-driver [tens]
    (drv/get-driver (dtt/tensor->buffer tens)))
  drv/PDeviceProvider
  (get-device [tens]
    (drv/get-device (dtt/tensor->buffer tens))))
