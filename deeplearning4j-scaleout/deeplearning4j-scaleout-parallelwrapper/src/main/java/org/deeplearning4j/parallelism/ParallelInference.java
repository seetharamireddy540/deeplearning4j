package org.deeplearning4j.parallelism;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.parallelism.inference.InferenceMode;
import org.deeplearning4j.parallelism.inference.observers.BasicInferenceObservable;
import org.deeplearning4j.parallelism.inference.observers.BasicInferenceObserver;
import org.deeplearning4j.parallelism.inference.InferenceObservable;
import org.deeplearning4j.parallelism.inference.observers.BatchedInferenceObservable;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Observer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class is simple wrapper for ParallelInference using batched input
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class ParallelInference {
    private Model model;
    private List<String> labels;
    private long nanos;
    private int workers;
    private int batchLimit;
    private InferenceMode inferenceMode;
    private int queueLimit;

    // this queue
    private BlockingQueue<InferenceObservable> observables;

    private AtomicLong sequenceId = new AtomicLong(0);
    private final Object locker = new Object();

    private InferenceWorker[] zoo;
    private ObservablesProvider provider;


    protected ParallelInference() {
        //
    }

    protected void init() {
        observables = new LinkedBlockingQueue<>(queueLimit);

        zoo = new InferenceWorker[workers];
        for (int i = 0; i < workers; i++) {
            zoo[i] = new InferenceWorker(i, model, observables);
            zoo[i].start();
        }


        if (inferenceMode == InferenceMode.BATCHED) {
            log.info("Initializing ObservablesProvider...");
            provider = new ObservablesProvider(nanos, batchLimit, observables);
        }
    }

    protected long getWorkerCounter(int workerIdx) {
        return zoo[workerIdx].getCounterValue();
    }

    public INDArray output(double[] input) {
        return output(Nd4j.create(input));
    }

    public INDArray output(float[] input) {
        return output(Nd4j.create(input));
    }

    public INDArray output(INDArray input) {
        // basically, depending on model type we either throw stuff to specific model, or wait for batch
        return output(new INDArray[] {input})[0];
    }

    public INDArray output(DataSet dataSet) {
        return output(dataSet.getFeatureMatrix());
    }

    public INDArray[] output(INDArray... input) {
        // basically, depending on model type we either throw stuff to specific model, or wait for batch

        BasicInferenceObserver observer = new BasicInferenceObserver();
        InferenceObservable observable;

        if (inferenceMode == InferenceMode.SEQUENTIAL) {
            observable = new BasicInferenceObservable(input);
            observable.addObserver(observer);
            try {
                observables.put(observable);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            observable = provider.setInput(observer, input);
        }



        try {
            // submit query to processing


            // and block until Observable returns
            //observer.wait();

            observer.waitTillDone();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return observable.getOutput();
    }


    public static class Builder {
        private Model model;
        private List<String> labels;
        private long nanos;
        private int workers = Nd4j.getAffinityManager().getNumberOfDevices();
        private int batchLimit = 32;
        private InferenceMode inferenceMode = InferenceMode.SEQUENTIAL;
        private int queueLimit = 64;

        public Builder(@NonNull ComputationGraph model) {
            this.model = model;
        }

        public Builder(@NonNull MultiLayerNetwork model) {
            this.model = model;
        }

        /**
         * This method allows you to define mode that'll be used during inference. Options are:
         *
         * SEQUENTIAL: Input will be sent to last-used worker unmodified.
         * BATCHED: Multiple inputs will be packed into single batch, and sent to last-used device.
         *
         * @param inferenceMode
         * @return
         */
        public Builder inferenceMode(@NonNull InferenceMode inferenceMode) {
            this.inferenceMode = inferenceMode;
            return this;
        }


        /**
         * This method allows you to specify String labels that'll be used as output to your input
         *
         * PLEASE NOTE: This method is optional, and applies to classification models only
         *
         * @param labels
         * @return
         */
        public Builder labels(@NonNull List<String> labels) {
            this.labels = labels;
            return this;
        }

        /**
         * This method defines, how many model copies will be used for inference.
         *
         * PLEASE NOTE: This method primarily suited for multi-GPU systems
         *
         * @param workers
         * @return
         */
        public Builder workers(int workers) {
            if (workers < 1)
                throw new IllegalStateException("Workers should be positive value");

            this.workers = workers;
            return this;
        }

        /**
         * This method defines, how many input samples can be batched within given time frame.
         *
         * PLEASE NOTE: This value has no effect in SEQUENTIAL inference mode
         *
         * @param limit
         * @return
         */
        public Builder batchLimit(int limit) {
            if (limit < 1)
                throw new IllegalStateException("Batch limit should be positive value");

            this.batchLimit = limit;
            return this;
        }

        /**
         * This method defines buffer queue size.
         *
         * Default value: 64
         *
         * @param limit
         * @return
         */
        public Builder queueLimit(int limit) {
            if (limit < 1)
                throw new IllegalStateException("Queue limit should be positive value");

            this.queueLimit = limit;
            return this;
        }

        /**
         * This method builds new ParallelInference instance
         *
         * @return
         */
        public ParallelInference build() {
            ParallelInference inference = new ParallelInference();
            inference.batchLimit = this.batchLimit;
            inference.queueLimit = this.queueLimit;
            inference.inferenceMode = this.inferenceMode;
            inference.labels = this.labels;
            inference.model = this.model;
            inference.nanos = this.nanos;
            inference.workers = this.workers;

            inference.init();

            return inference;
        }
    }


    /**
     * This class actually does inference with respect to device affinity
     *
     */
    private class InferenceWorker extends Thread implements Runnable {
        private BlockingQueue<InferenceObservable> inputQueue;
        private AtomicBoolean shouldWork = new AtomicBoolean(true);
        private AtomicBoolean isStopped = new AtomicBoolean(false);
        private Model protoModel;
        private Model replicatedModel;
        private AtomicLong counter = new AtomicLong(0);

        private InferenceWorker(int id, @NonNull Model model, @NonNull BlockingQueue inputQueue) {
            this.inputQueue = inputQueue;
            this.protoModel = model;

            this.setDaemon(true);
            this.setName("InferenceThread-" + id);
        }

        protected long getCounterValue() {
            return counter.get();
        }

        @Override
        public void run() {
            try {
                // model should be replicated & initialized here
                if (protoModel instanceof ComputationGraph) {
                    this.replicatedModel = new ComputationGraph(ComputationGraphConfiguration
                                    .fromJson(((ComputationGraph) protoModel).getConfiguration().toJson()));
                    ((ComputationGraph) this.replicatedModel).init();

                    synchronized (locker) {
                        ((ComputationGraph) this.replicatedModel).setParams(protoModel.params());

                        if (Nd4j.getExecutioner() instanceof GridExecutioner)
                            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();
                    }
                } else if (protoModel instanceof MultiLayerNetwork) {
                    this.replicatedModel = new MultiLayerNetwork(MultiLayerConfiguration
                                    .fromJson(((MultiLayerNetwork) protoModel).getLayerWiseConfigurations().toJson()));
                    ((MultiLayerNetwork) this.replicatedModel).init();

                    synchronized (locker) {
                        ((MultiLayerNetwork) this.replicatedModel).setParams(protoModel.params());

                        if (Nd4j.getExecutioner() instanceof GridExecutioner)
                            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();
                    }
                }

                while (shouldWork.get()) {
                    InferenceObservable request = inputQueue.take();

                    if (request != null) {
                        counter.incrementAndGet();

                        // FIXME: get rid of instanceof here, model won't change during runtime anyway
                        if (replicatedModel instanceof ComputationGraph) {
                            INDArray[] output = ((ComputationGraph) replicatedModel).output(false, request.getInput());
                            request.setOutput(output);
                        } else if (replicatedModel instanceof MultiLayerNetwork) {
                            INDArray output = ((MultiLayerNetwork) replicatedModel).output(request.getInput()[0]);
                            request.setOutput(output);
                        }


                    } else {
                        // just do nothing, i guess and hope for next round?
                    }
                }
            } catch (InterruptedException e) {
                // do nothing
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            isStopped.set(true);
        }

        protected void shutdown() {
            shouldWork.set(false);
            while (!isStopped.get()) {
                // block until main loop is finished
            }
        }
    }


    protected static class ObservablesProvider {
        private BlockingQueue<InferenceObservable> targetQueue;
        private long nanos;
        private int batchLimit;

        private volatile BatchedInferenceObservable currentObservable;
        private final Object locker = new Object();

        protected ObservablesProvider(long nanos, int batchLimit, @NonNull BlockingQueue<InferenceObservable> queue) {
            this.targetQueue = queue;
            this.nanos = nanos;
            this.batchLimit = batchLimit;
        }


        protected InferenceObservable setInput(@NonNull Observer observer, INDArray... input) {
            synchronized (locker) {
                boolean isNew = false;
                if (currentObservable == null || currentObservable.getCounter() >= batchLimit
                                || currentObservable.isLocked()) {
                    isNew = true;
                    currentObservable = new BatchedInferenceObservable();
                }

                currentObservable.setInput(input);
                currentObservable.addObserver(observer);

                try {
                    if (isNew)
                        targetQueue.put(currentObservable);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                return currentObservable;
            }
        }
    }
}
