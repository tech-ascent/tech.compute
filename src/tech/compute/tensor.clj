(ns tech.compute.tensor
  "Tensor library used to implement the basic math abstraction fairly easily
  implementable across a wide range of compute devices.  This abstraction is meant to
  provide a language in which to implement some amount of functionalty especially useful
  in quickly testing out algorithmic updates or moving data to/from external libraries.
  As such, it has extensive support for reshape/select/transpose type operations but
  only nominal base math facilities are provided by default.

There is an implicit assumption throughout this file that implementations will loop
  through smaller entities instead of throwing an exception if sizes don't match.  This
  is referred to as broadcasting in numpy
  (https://docs.scipy.org/doc/numpy/user/basics.broadcasting.html).

It does mean, however, that certain conditions that would actually be error cases are
  harder to detect because one has to check for remainders being zero (which potentially
  could cause a divide by zero error) instead of just checking for equality.


For binary operations there are four forms:

y = a*x op b*y
result = a*x op b*y.
y[idx] = a*x[idx] op b*y[idx]
result[idx] = a*x[idx] op b*y[idx]

Op may be: [:+ :* :/].

In the non-indexed cases the element counts of y or x may differ but they need to be
  commensurate meaning that the smaller evenly divides the larger.  When writing to
  result it is important that result is as large as the largest.  This is a relaxation
  of the numpy broadcasting rules to allow more forms of broadcasting; the check is that
  the remainder is zero; not that the smaller dimension is 1.


In general we want as much error checking and analysis done in this file as opposed to
  at the implementation level (compute stream level) so that different implementations
  of this duplicate the least number of possible operations and so their edge cases
  agree to the extent possible."
  (:require [tech.compute.driver :as compute-drv]
            [tech.compute :as compute]
            [tech.datatype :as dtype]
            [tech.datatype.base :as dtype-base]
            [tech.datatype.java-primitive :as primitive]
            [tech.datatype.java-unsigned :as unsigned]
            [tech.datatype.jna :as dtype-jna]
            [tech.jna :as jna]
            [clojure.core.matrix.protocols :as mp]
            [mikera.vectorz.matrix-api]
            [clojure.core.matrix :as m]
            [tech.resource :as resource]
            [tech.compute.tensor.math :as tm]
            [tech.compute.tensor.dimensions :as dims]
            [tech.compute.tensor.utils :refer [when-not-error
                                               reversev
                                               all-combinations]]
            [clojure.core.matrix.impl.pprint :as corem-pp]
            [tech.compute.tensor.protocols :as tens-proto]
            [tech.compute.tensor.defaults :as defaults]
            [tech.compute.tensor.error-checking :as error-checking]
            [tech.compute.tensor.details :as details]
            [tech.compute.registry :as registry])
  (:import [java.io Writer]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(declare reinterpret-tensor typed-assign! make-dense tensor-or-number)

;;Tensors are a tuple of device (driver for now) dimensions and index system and buffer.
(defrecord Tensor [dimensions buffer]
  dtype-base/PDatatype
  (get-datatype [tensor] (dtype/get-datatype (:buffer tensor)))
  dtype-base/PContainerType
  (container-type [tensor] (dtype-base/container-type buffer))

  ;;If we are simple tensor && our buffer allows it we can act like a raw datatype
  ;;storage container.  Users can bypass the simple check by getting the buffer and
  ;;calling the appropriate methods on the buffer directly.
  dtype-base/PAccess
  (set-value! [tensor idx value]
    (error-checking/ensure-simple-tensor tensor)
    (dtype-base/set-value! buffer idx value))
  (set-constant! [tensor idx value n-elems]
    (error-checking/ensure-simple-tensor tensor)
    (dtype-base/set-constant! buffer idx value n-elems))
  (get-value [tensor idx]
    (error-checking/ensure-simple-tensor tensor)
    (dtype-base/get-value buffer idx))
  dtype-base/PCopyRawData
  (copy-raw->item! [tensor dest dest-offset options]
    (error-checking/ensure-simple-tensor tensor)
    (dtype-base/copy-raw->item! buffer dest dest-offset options))
  dtype-base/PPrototype
  (from-prototype [tensor datatype shape]
    (->Tensor (dims/dimensions shape)
              (dtype/from-prototype buffer
                                    :datatype datatype
                                    :shape shape)))
  primitive/PToBuffer
  (->buffer-backing-store [tensor]
    (error-checking/ensure-simple-tensor tensor)
    (primitive/->buffer-backing-store buffer))
  primitive/PToArray
  (->array [tensor]
    (error-checking/ensure-simple-tensor tensor)
    (primitive/->array buffer))
  (->array-copy [tensor]
    (error-checking/ensure-simple-tensor tensor)
    (primitive/->array-copy buffer))
  jna/PToPtr
  (->ptr-backing-store [tensor]
    (error-checking/ensure-simple-tensor tensor)
    (jna/->ptr-backing-store buffer))

  compute-drv/PDeviceProvider
  (get-device [tensor] (compute/->device buffer))
  compute-drv/PDriverProvider
  (get-driver [tensor] (compute/->driver buffer))
  mp/PElementCount
  (element-count [tensor]
    (dims/ecount dimensions))
  mp/PDimensionInfo
  (dimensionality [m] (count (mp/get-shape m)))
  (get-shape [m] (dims/shape dimensions))
  (is-scalar? [m] false)
  (is-vector? [m] true)
  (dimension-count [m dimension-number]
    (let [shape (mp/get-shape m)]
      (if (<= (count shape) (long dimension-number))
        (get shape dimension-number)
        (throw (ex-info "Array does not have specific dimension"
                        {:dimension-number dimension-number
                         :shape shape})))))
  mp/PVectorView
  (as-vector [m]
    (when (error-checking/simple-tensor? m)
      (reinterpret-tensor m (dims/dimensions [(dtype/ecount m)]))))

  mp/PVectorisable
  (to-vector [m]
    (reinterpret-tensor (make-dense m) (dims/dimensions [(dtype/ecount m)])))

  mp/PAssignment
  (assign! [dest src]
    (details/typed-assign! dest (tensor-or-number src) {})
    dest)

  tens-proto/PIsTensor
  (tensor? [item] true)
  (dense? [item] (dims/dense? dimensions))
  (tensor->dimensions [item] dimensions)
  (tensor->buffer [item] buffer))


(defn construct-tensor
  ^Tensor [dimensions buffer]
  (let [buffer-ecount (dtype/ecount buffer)
        shape (dims/shape dimensions)]
    (->Tensor dimensions buffer)))


(defn acceptable-tensor-buffer?
  [item]
  (error-checking/acceptable-tensor-buffer? item))


(defn tensor? [item]
  (tens-proto/tensor? item))


(defn as-tensor
  "In-place make this a tensor."
  [item]
  (if (tensor? item)
    item
    (when (acceptable-tensor-buffer? item)
      (construct-tensor (dims/dimensions (dtype/shape item)) item))))


(defn ensure-tensor
  [item]
  (if-let [retval (as-tensor item)]
    retval
    (throw (ex-info "Arugment is not tensor or directly convertible to tensor"
                    {:item-type (type item)}))))


(defn- tensor-or-number
  [item]
  (if (number? item)
    item
    (ensure-tensor item)))


(defn reinterpret-tensor
  "Create a new tensor with new dimensions.  This is like an in place reinterpretation
  of the data."
  ^Tensor [old-tensor new-dimensions]
  (construct-tensor new-dimensions
                    (tens-proto/tensor->buffer
                     (ensure-tensor old-tensor))))


(defn ->tensor
  "Create a tensor from the data by copying the data at least once."
  [data & {:keys [datatype unchecked? shape stream force-copy?]
           :as options}]
  (if (tensor? data)
    data
    (let [stream (defaults/infer-stream options)
          driver (compute-drv/get-driver stream)
          datatype (defaults/datatype datatype)
          data-shape (or shape (dtype/shape data))
          n-elems (long (apply * data-shape))
          device (compute/->device stream)
          {host-buffer :return-value
           resource-seq :resource-seq}
          (resource/return-resource-seq
           (compute-drv/allocate-host-buffer
            (compute/->driver device)
            n-elems datatype options))
          host-buffer-release-fn #(resource/release-resource-seq resource-seq)
          [host-buffer copy-count]
          (dtype/copy-raw->item! data host-buffer 0 {:unchecked? unchecked?})]
      (when-not (= (long (apply * 1 data-shape))
                   (long copy-count))
        (throw (ex-info "Failed to copy the expected amount into host buffer"
                        {:shape shape
                         :copy-ecount copy-count
                         :expected-ecount (apply * 1 shape)})))
      (let [result-buffer
            ;;If the host buffer is an acceptable device buffer, then we can just
            ;;stop here.
            (if (compute-drv/acceptable-device-buffer?
                 device host-buffer)
              (do
                ;;Track any resources created during creation of host buffer
                (resource/make-resource host-buffer-release-fn)
                ;;Return host buffer; everything is fine.
                host-buffer)
              (let [dev-buffer (compute-drv/allocate-device-buffer
                                device n-elems datatype options)]
                (compute/copy-host->device host-buffer 0 dev-buffer
                                           0 n-elems :stream stream)
                ;;The wait here is so that we can clean up the host buffer.
                (compute/sync-with-host stream)
                ;;Release host buffer if necessary
                (host-buffer-release-fn)
                dev-buffer))]
        (if (= 1 (count shape))
          result-buffer
          (construct-tensor (dims/dimensions data-shape) result-buffer))))))


(defn new-tensor
  [shape & {:keys [datatype init-value stream]
            :or {init-value 0}
            :as options}]
  (let [dimensions (dims/dimensions shape)
        n-elems (long (apply * shape))
        stream (defaults/infer-stream options)
        datatype (defaults/datatype datatype)
        device (compute/->device stream)
        dev-buffer (compute/allocate-device-buffer device n-elems datatype)
        retval (construct-tensor dimensions dev-buffer)]
    (when init-value
      (m/assign! retval init-value))
    (if (= 1 (count shape))
      dev-buffer
      retval)))


(defn from-prototype
  "New tensor just like this one (same device/driver,etc)"
  [src & {:keys [datatype shape]}]
  (dtype/from-prototype src :datatype datatype :shape shape))


(defn- elide-type
  "If we had a conversion to tensor then we should get
same type back."
  [src dst]
  (if (tensor? src)
    dst
    (tens-proto/tensor->buffer dst)))


(defn clone
  "Clone this tensor, keeping as many details as possible identical.
This method does type elision, so in most cases it will return something
of the same type as passed in."
  [src & {:keys [datatype]
          :as options}]
  (let [dst (-> (ensure-tensor src)
                (from-prototype :datatype datatype)
                (mp/assign! (ensure-tensor src)))]
    (elide-type src dst)))


(defn clone-to-device
  "Clone this tensor, creating a new one on the currently bound device.
Does not to type elision."
  [src & {:keys [datatype]
          :as options}]
  (let [src (ensure-tensor src)
        dst (new-tensor (dtype/shape src)
                        :datatype (or datatype
                                      (dtype/get-datatype src))
                        :init-value nil
                        :stream (:stream options))]
    (mp/assign! dst src)))


(defn dense? [item]
  (-> (ensure-tensor item)
      tens-proto/dense?))


(def strided? (complement dense?))


(defn simple-tensor?
  [tensor]
  (-> (ensure-tensor tensor)
      error-checking/simple-tensor?))



(defn simple-tensor?
  [tensor]
  (error-checking/simple-tensor? tensor))


(defn tensor->buffer [item]
  (-> (ensure-tensor item)
      tens-proto/tensor->buffer))

(defn tensor->dimensions
  [item]
  (-> (ensure-tensor item)
      tens-proto/tensor->dimensions))

;;Tensor functions that rely on global protocols.
(defn scalar?
  [item] (number? item))

(defn get-datatype
  [tensor]
  (dtype/get-datatype tensor))

(defn shape
  [tensor]
  (dtype/shape tensor))

(defn as-vector
  [tensor]
  (m/as-vector (ensure-tensor tensor)))

(defn to-vector
  [tensor]
  (m/to-vector (ensure-tensor tensor)))

(defn ecount
  ^long [tensor]
  (long (mp/element-count (ensure-tensor tensor))))

(defn assign!
  [dest src]
  (mp/assign! (ensure-tensor dest) (tensor-or-number src))
  dest)


(defn- dimensions->num-columns
  ^long [dimensions]
  (get-in dimensions [:shape 1] 1))


(defn strides
  [tensor]
  (:strides (tensor->dimensions tensor)))


(defn- tensor->column-stride
  ^long [tensor]
  (dims/dimensions->column-stride
   (tensor->dimensions tensor)))


(defn- tensor->num-columns
  ^long [tensor]
  (dimensions->num-columns
   (tensor->dimensions tensor)))


(defn- tensor->device
  [tensor]
  (compute/->device (ensure-tensor tensor)))


(defn tensor->buffer
  [tensor]
  (tens-proto/tensor->buffer (ensure-tensor tensor)))


(defn tensor->2d-shape
  [tensor]
  (dims/->2d-shape (tensor->dimensions tensor)))


(defn tensor->batch-shape
  [tensor]
  (dims/->batch-shape (tensor->dimensions tensor)))


(defn in-place-reshape
  [tensor shape]
  (assoc (ensure-tensor tensor)
         :dimensions (dims/in-place-reshape (tensor->dimensions tensor)
                                            shape)))


(defn as-column-vector
  [tensor]
  (when-not-error (or (= 1 (tensor->num-columns tensor))
                      (dense? tensor))
    "Column vectors must either be dense or have num-columns = 1"
    {:dense? (dense? tensor)
     :num-columns (tensor->num-columns tensor)})
  (reinterpret-tensor tensor (dims/dimensions [(ecount tensor) 1])))

(defn as-row-vector
  [tensor]
  (when-not-error (or (= 1 (tensor->num-columns tensor))
                      (dense? tensor))
    "Row vectors must either be dense or have num-columns = 1"
    {:dense? (dense? tensor)
     :num-columns (tensor->num-columns tensor)})
  (reinterpret-tensor tensor (dims/dimensions [(ecount tensor)])))


(defn tensor->batch-size
  ^long [tensor]
  (dims/->least-rapidly-changing-dimension
   (tensor->dimensions tensor)))


(defn as-batch-matrix
  "As a 2d matrix of shape [least-rapidly-changing-dimension everything-else]"
  ^Tensor [tensor]
  (in-place-reshape tensor (tensor->batch-shape tensor)))


(defn as-2d-matrix
  "As a 2d matrix of shape [everything-else most-rapidly-changin-dimension]"
  ^Tensor [tensor]
  (in-place-reshape tensor (tensor->2d-shape tensor)))


(defn as-dense
  "As dense implies that a memcpy call would succeed as one expects.  This means
  actually 2 conditions are checked:
1.  dense?
2.  dimensions-monotonic-increasing"
  [tensor]
  (when (and (dense? tensor)
             (dims/access-increasing?
              (tensor->dimensions tensor)))
    tensor))


(defn make-dense
  [tensor]
  (or (as-dense tensor)
      (clone tensor)))


(defn transpose
  "Transpose the tensor returning a new tensor that shares the backing store but indexes
  into it in a different order.
  Dimension 0 is the leftmost (greatest) dimension:

  (transpose tens (range (count (shape tens))))

  is the identity operation."
  [tensor reorder-vec]
  (-> (ensure-tensor tensor)
      (assoc :dimensions (dims/transpose (tensor->dimensions tensor)
                                         reorder-vec))))


(defn select
  "Limited implementation of the core.matrix select function call.
Same rules apply *Except* if you pass in an array of numbers for a dimension
then they must be contiguous and monotonically increasing (a proper inclusive range).
This is due to limitations of the current gpu implementation and a strong reluctance
to add complexity there.  There must be an entry for every dimension of the tensor.
see:
https://cloojure.github.io/doc/core.matrix/clojure.core.matrix.html#var-select"
  [tensor & args]
  (let [tensor (ensure-tensor tensor)
        select-result (apply dims/select (tensor->dimensions tensor) args)
        {:keys [dimensions elem-offset]} select-result
        tens-buffer (tens-proto/tensor->buffer tensor)
        new-buffer (compute/sub-buffer tens-buffer elem-offset
                                       (- (dtype/ecount tens-buffer)
                                          (long elem-offset)))]
    (construct-tensor dimensions new-buffer)))


(defn subvector
  [tensor offset & {:keys [length]}]
  (when-not-error (>= (long offset) 0)
    "Offset must be >= 0"
    {:offset offset})
  (select (as-vector tensor) (range offset (or (+ (long offset)
                                                  (long length))
                                               (ecount tensor)))))


(defn submatrix
  "Create a sub matrix of tensor.  Tensor will be interpreted as width being n-cols
and the rest of the dimensions being squashed into n-rows."
  [tensor row-start row-length col-start col-length]
  (let [tensor (ensure-tensor tensor)]
    (when-not-error (= 2 (count (shape tensor)))
      "Tensor is not a 2d matrix"
      {:tensor-shape (shape tensor)})
    (select tensor
            (range row-start (+ (long row-start) (long row-length)))
            (range col-start (+ (long col-start) (long col-length))))))



(defn rows
  "Returns a vector rows of dense vectors."
  [tensor]
  (let [tensor (ensure-tensor tensor)]
    (let [[n-rows n-cols] (tensor->2d-shape tensor)]
      (map (fn [row-idx]
             (select tensor row-idx (range n-cols)))
           (range n-rows)))))


(defn columns
  "Returns a vector of matrixes with width of 1 but large column strides."
  [tensor]
  (let [tensor (ensure-tensor tensor)
        [n-rows n-cols] (tensor->2d-shape tensor)]
    (map (fn [col-idx]
           (select tensor (range n-rows) col-idx))
         (range n-cols))))


(def unary-operations
  #{:floor :ceil :round :- :tanh :logistic :exp :sqrt :noop})


(defn unary-op!
  "dest[idx] = op(alpha * x)"
  [dest alpha x op & [options]]
  (details/unary-op! (ensure-tensor dest) alpha
                     (tensor-or-number x) op options)
  dest)


(def binary-operations
  #{:+ :- :* :/ :max :min :bit-and :bit-xor :eq :> :>= :< :<=})


(defn binary-op!
  "Perform the operation:
dest = alpha * x op beta * y.
x or y may be a scalar, dest must not be.
Datatypes must match."
  [dest alpha x beta y op & [options]]
  (details/binary-op! (ensure-tensor dest) alpha
                      (tensor-or-number x) beta
                      (tensor-or-number y) op options)
  dest)


(def ternary-operations
  #{:select})


(defn ternary-op!
  "Perform the elementwise operation dest = op( alpha * x, beta * y, gamma * z ) dest
  tensor and must not alias any other arguments.  There is no accumulator version of
  these operations at this time in order to keep kernel permutations low (3 backend
  permutations).

  x, y, z can be constants or tensors.

  operations:
  select: dest = (if (>= x 0) y z)"
  [dest alpha x beta y gamma z op & [options]]
  (details/ternary-op! (ensure-tensor dest)
                       alpha (tensor-or-number x)
                       beta (tensor-or-number y)
                       gamma (tensor-or-number z) op options)
  dest)


(def unary-reduction-operations
  #{:max :min :sum :mean :magnitude-squared :magnitude})


(defn unary-reduce!
  "Vector operations operate across the last dimension and produce 1 result.
output = op((alpha*input))
Output must be a [xyz 1] tensor while input is an [xyz n] tensor;
the reduction will occur across the n axis with the results placed in output.
The leading dimensions of both vectors must match."
  [output alpha input op & [options]]
  (details/unary-reduce! (ensure-tensor output) alpha
                         (tensor-or-number input) op options))


(defn gemm!
  "C = alpha * (trans-a? A) * (trans-b? B) + beta * C."
  ^Tensor [C trans-a? trans-b? alpha A B beta & [options]]
  (let [C (ensure-tensor C)
        A (ensure-tensor A)
        B (ensure-tensor B)]
    (error-checking/external-library-check! "gemm!" C A B)
    (error-checking/ensure-matrix C)
    (error-checking/ensure-matrix A)
    (error-checking/ensure-matrix B)
    (let [A-dims (tensor->dimensions A)
          B-dims (tensor->dimensions B)
          C-dims (tensor->dimensions C)
          a-in-place-trans? (not (dims/access-increasing? A-dims))
          b-in-place-trans? (not (dims/access-increasing? B-dims))
          trans-a? (if a-in-place-trans? (not trans-a?) trans-a?)
          trans-b? (if b-in-place-trans? (not trans-b?) trans-b?)
          A (if a-in-place-trans? (transpose A [1 0]) A)
          B (if b-in-place-trans? (transpose B [1 0]) B)
          A-dims (tensor->dimensions A)
          B-dims (tensor->dimensions B)
          [a-row-count a-col-count :as a-shape] (dims/trans-2d-shape trans-a? A-dims)
          [b-row-count b-col-count :as b-shape] (dims/trans-2d-shape trans-b? B-dims)
          [c-row-count c-col-count :as c-shape] (dims/->2d-shape C-dims)
          a-row-count (long a-row-count)
          a-col-count (long a-col-count)
          b-row-count (long b-row-count)
          b-col-count (long b-col-count)
          c-row-count (long c-row-count)
          c-col-count (long c-col-count)]
      (when-not-error (every? #(= 1 (dims/matrix-element-stride %))
                              [A-dims B-dims C-dims])
        "Element strides must be 1 for gemm"
        {:element-strides (mapv dims/matrix-element-stride [A-dims B-dims C-dims])})
      (when-not-error (dims/access-increasing? C-dims)
        "C cannot be in-place-transposed."
        {:c-dims C-dims})

      (when-not-error (= a-col-count b-row-count)
        (format "A %s col count doesn't match B %s row count" a-shape b-shape)
        {:a-shape a-shape
         :b-shape b-shape})
      (when-not-error (= a-row-count c-row-count)
        (format "C %s row count doesn't match A %s row count" c-shape a-shape)
        {:a-shape a-shape
         :c-shape c-shape})
      (when-not-error (= b-col-count c-col-count)
        (format "C %s col count doesn't match B %s col count" c-shape b-shape)
        {:b-shape b-shape
         :c-shape c-shape})
      (tm/gemm! (defaults/infer-stream options C)
                C (dims/matrix-column-stride C-dims)
                trans-a? trans-b? alpha
                A a-row-count a-col-count (dims/matrix-column-stride A-dims)
                B b-col-count (dims/matrix-column-stride B-dims)
                beta)))
  C)


(defn gaussian-distribution
  "Create a Gaussian distribution description"
  [& {:keys [mean variance]
      :or {mean 0
           variance 1}}]
  {:type :gaussian
   :mean (double mean)
   :variance (double variance)})


(defn flat-distribution
  "Create a flat distribution description.
Flat (equal) distribution including minimum but excluding maximum
[minimum maximum)"
  [& {:keys [minimum maximum]
      :or {minimum 0 maximum 1}}]
  (when-not-error (< (double minimum)
                     (double maximum))
    "Minimum must be less than maximum"
    {:minimum minimum
     :maximum maximum})
  {:type :flat
   :minimum (double minimum)
   :maximum (double maximum)})


(defn- valid-distribution?
  "This screams for spec."
  [{:keys [type] :as distribution}]
  (when-not-error (or (= type :gaussian)
                      (= type :flat))
    "Invalid distibution type"
    {:valid-types #{:flat :gaussian}
     :type type}))


(defn rand!
  "Generate a pool of random numbers.
Due to cuda limitations, this function is limited to floating point numbers."
  [dest distribution & {:as options}]
  (let [dest (ensure-tensor dest)]
    (when-not-error (= :float32 (get-datatype dest))
      "Can only generate rands into floating point buffers"
      {:expected-datatype :float32
       :received-datatype (get-datatype dest)})
    (when-not-error (and (dense? dest)
                         (dims/access-increasing? (tensor->dimensions dest)))
      "Rand generation must have simple dense buffers" {})
    (valid-distribution? distribution)
    (tm/rand! (defaults/infer-stream options dest) dest distribution))
  dest)


(defn normalize!
  "Ensure each vector of the last dimension of dest has length radius-length.
Epsilon is used to avoid divide-by-zero conditions.  This operation can also
be seen as a projection to the surface of a hypersphere of radius radius-length."
  [dest mag-vec radius-length epsilon & {:as options}]
  (unary-reduce! mag-vec 1.0 dest :magnitude )
  ;;Ensure a zero doesn't cause a nan.
  (binary-op! mag-vec 1.0 mag-vec 1.0 1e-6 :+)
  (binary-op! dest 1.0 dest (/ 1.0 (double radius-length)) mag-vec :/))


(defn constrain-inside-hypersphere!
  "Like normalize, but only shorten vectors that are too long.  So instead of
projecting to the surface of the hypersphere like normalize does, do a <= operation."
  [dest mag-vec radius-length]
  (unary-reduce! mag-vec 1.0 dest :magnitude)
  ;;Subtract radius-length from the magnitude vector.  This means scales below the
  ;;target are now less than 0
  (binary-op! mag-vec 1.0 mag-vec 1.0 radius-length :-)
  ;;Set scales less than 0 to 0 and do not change ones more than zero.
  (ternary-op! mag-vec 1.0 mag-vec 0.0 0.0 1.0 mag-vec :select)
  ;;Add radius-length back into the mix.  This means that items with scale <=
  ;;radius-length are now radius-length and items with scale > radius-length are
  ;;whatever their original lengths would be.
  (binary-op! mag-vec 1.0 mag-vec 1.0 radius-length :+)
  ;;Last step, multiple dest by radius-length/mag-len.  If mag-len is radius-length,
  ;;then no change.  and we know from the above operations that only items of mag-len or
  ;;greater will be changed.
  (binary-op! dest 1.0 dest (/ 1.0 (double radius-length)) mag-vec :/))


(defn enable-cpu-tensors!
  "Enables a version of the tensors that run on the cpu and that use netlib blas
  for operations."
  []
  (defaults/unsafe-set-stream!
   (fn [_]
     (require 'tech.compute.cpu.driver)
     (require 'tech.compute.cpu.tensor-math)
     (-> (registry/driver :tech.compute.cpu.driver)
         compute/default-device
         compute/default-stream))))


(defn copy-to-java-type
  "The options map in this case also contains potentially
{:unchecked?} as the dtype/copy method is used."
  [dest ^Tensor src & [options]]
  (resource/with-resource-context
   (let [tensor (make-dense src)
         n-elems (ecount tensor)
         device (tensor->device tensor)
         stream (defaults/infer-stream options src)
         host-buffer (compute/allocate-host-buffer
                      (compute/->driver device)
                      n-elems (dtype/get-datatype tensor))]
     (compute/copy-device->host (tensor->buffer tensor) 0
                                host-buffer 0 n-elems
                                :stream stream)
     ;;Block until the copy completes.
     (compute/sync-with-host stream)
     (dtype/copy! host-buffer 0 dest 0 n-elems options)
     dest)))


(defn to-array-of-type
  [^Tensor tensor datatype]
  (copy-to-java-type (dtype/make-array-of-type datatype (ecount tensor))
                     tensor))

(defn to-double-array
  ^doubles [tensor]
  (to-array-of-type tensor :float64))


(defn to-float-array
  ^floats [tensor]
  (to-array-of-type tensor :float32))

(defn to-core-matrix
  [tensor]
  (let [tensor (ensure-tensor tensor)
        retval (m/new-array :vectorz (shape tensor))
        double-data (mp/as-double-array retval)]
    (copy-to-java-type double-data tensor)
    retval))

(defn to-core-matrix-vector
  [tensor]
  (m/as-vector (to-core-matrix tensor)))


(defn to-jvm
  "Conversion to storage that is efficient for the jvm.
  Base storage is either jvm-array or persistent-vector."
  [item & {:keys [datatype base-storage]
           :or {datatype :float64
                base-storage :persistent-vector}}]
  ;;Get the data off the device
  (let [data-array (to-array-of-type item datatype)
        item-shape (shape item)
        item-ecount (ecount data-array)
        column-len (long (last item-shape))
        n-columns (quot item-ecount column-len)
        base-data
        (->> (range n-columns)
             (map (fn [col-idx]
                    (let [col-offset (* column-len (long col-idx))]
                      (case base-storage
                        :jvm-array
                        (let [retval (dtype/make-array-of-type datatype
                                                               column-len)]
                          (dtype/copy! data-array col-offset
                                       retval 0 column-len {:unchecked? true}))
                        :persistent-vector
                        (mapv #(dtype/get-value data-array (+ (long %1)
                                                              col-offset))
                              (range column-len)))))))
        partitionv (fn [& args]
                     (map vec (apply partition args)))
        partition-shape (->> (rest item-shape)
                             drop-last
                             reverse)]
    (if (> (count item-shape) 1)
      (->> partition-shape
           (reduce (fn [retval part-value]
                     (partitionv part-value retval))
                   base-data)
           vec)
      (first base-data))))


(defn tensor->string
  ^String [tens & {:keys [print-datatype]
                   :or {print-datatype :float64}}]
  (format "#tech.compute.tensor.Tensor<%s>%s\n%s"
          (name (dtype/get-datatype tens))
          (dtype/shape tens)
          (corem-pp/pm (to-jvm tens :datatype print-datatype))))


(defmethod print-method Tensor
  [tens w]
  (.write ^Writer w (tensor->string tens)))
