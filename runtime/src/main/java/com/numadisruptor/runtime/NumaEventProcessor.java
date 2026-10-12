package com.numadisruptor.runtime;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.ProducerType;
import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.optimizer.CommunicationGraph;
import com.numadisruptor.optimizer.PlacementConstraints;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.optimizer.PlacementPlan;
import com.numadisruptor.topology.NumaTopology;
import com.numadisruptor.topology.TopologyDiscovery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs a partitioned Disruptor pipeline with placement decided by the NUMA optimizer.
 *
 * <h2>Structure</h2>
 * Each of {@code partitions} independent streams owns a chain of rings (see {@link WorkloadSpec}). Workers are of three kinds:
 * <b>producers</b> (created and pinned by this class, running application {@link ProducerTask}s), <b>forwarders</b>
 * ({@link CrossNodeBridge}s applying a {@link StageFunction}) and <b>final consumers</b> (application
 * {@link EventHandler}s, shared out per {@link ConsumptionMode}). Alternatively application threads may call
 * {@link #tryPublish}/{@link #publish}; those are routed to a partition by the {@link EventRouter}.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Per-partition FIFO: events of one partition reach every consumer in ring order. With several producers the order
 *       between producers is the (arbitrary but single, agreed) claim order; each producer's own events stay in order.</li>
 *   <li>No global order across partitions.</li>
 *   <li>Backpressure: a full ring never loses or overwrites events; producers see {@code claim() == -1} / spin, bounded by
 *       the processor leaving the RUNNING state.</li>
 *   <li>{@link #stop(Duration)} drains: it stops producers, then waits ring by ring (in pipeline order) until every
 *       published event has been consumed, and throws if a worker failed or the drain timed out.</li>
 * </ul>
 */
public final class NumaEventProcessor<E> implements AutoCloseable {
    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    /**
     * Counters; all except {@code failedEvents} are exact only after producers finished. {@code producerStalls} counts every
     * failed claim attempt (spin iterations, so it grows with time spent blocked); {@code producerStallEpisodes} counts how
     * many times a producer entered the blocked state.
     */
    public record Stats(long producerStalls, long externalStalls, long failedEvents, long frameworkPublished,
                        long producerStallEpisodes) { }

    private final WorkloadSpec spec;
    private final EventFactory<E> eventFactory;
    private final ConsumerFactory<E> consumerFactory;
    private final StageFunction<E> stageFunction;
    private final WaitStrategyKind waitKind;
    private final PlacementPolicy policy;
    private final PlacementPlan explicitPlan;
    private final NumaTopology topology;
    private final CpuAffinity affinity;
    private final PlacementConstraints constraints;
    private final PlacementCostModel costModel;
    private final EventRouter router;
    private final Duration startTimeout;
    private final RingInit ringInit;

    private volatile State state = State.NEW;
    private volatile boolean aborting;
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final AtomicLong failedEvents = new AtomicLong();
    private final LongAdder externalStalls = new LongAdder();
    /** External publishers currently between the RUNNING check and the end of their publish; stop() waits for it to reach 0. */
    private final LongAdder inFlightPublishers = new LongAdder();
    private final List<List<NumaDisruptorFactory.Ring<E>>> rings = new ArrayList<>();
    private ProducerScheduler<E> scheduler;
    private PlacementPlan plan;
    private long optimizerNanos;

    private NumaEventProcessor(final Builder<E> b) {
        this.spec = new WorkloadSpec(b.partitions, b.producersPerPartition, b.forwardingStages, b.consumersPerPartition, b.mode,
            b.ringBufferSize, b.externalPublishers, b.eventsPerSecondPerPartition, b.payloadBytes, b.consumerBatch);
        this.eventFactory = Objects.requireNonNull(b.eventFactory, "eventFactory");
        this.consumerFactory = Objects.requireNonNull(b.consumerFactory, "consumerFactory");
        if (b.forwardingStages > 0 && b.stageFunction == null) {
            throw new IllegalArgumentException("forwardingStages > 0 requires a stageFunction");
        }
        if (b.policy == PlacementPolicy.EXPLICIT && b.explicitPlan == null) {
            throw new IllegalArgumentException("PlacementPolicy.EXPLICIT requires placementPlan(...)");
        }
        this.stageFunction = b.stageFunction;
        this.waitKind = b.waitKind;
        this.policy = b.policy;
        this.explicitPlan = b.explicitPlan;
        this.topology = b.topology != null ? b.topology : TopologyDiscovery.discover();
        this.affinity = b.affinity != null ? b.affinity : CpuAffinity.system();
        this.constraints = b.constraints;
        this.costModel = b.costModel != null ? b.costModel : PlacementCostModel.fromTopology(topology);
        this.router = b.router;
        this.startTimeout = b.startTimeout;
        this.ringInit = b.ringInit;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Start a processor whose partitions are fed only by external threads ({@link #publish}). */
    public void start() {
        start(null);
    }

    /**
     * Resolve the placement, build and pin everything, then start.
     *
     * @param producerTask run once on each framework producer thread; required iff {@code producersPerPartition > 0}
     * @throws PlacementException if pinning fails or the plan is invalid; nothing is left running in that case
     * @throws com.numadisruptor.optimizer.InfeasiblePlacementException if the workload does not fit the allowed CPUs/nodes
     */
    public synchronized void start(final ProducerTask<E> producerTask) {
        if (state != State.NEW) {
            throw new IllegalStateException("already started (state " + state + ")");
        }
        if (spec.producersPerPartition() > 0 && producerTask == null) {
            throw new IllegalArgumentException("producersPerPartition > 0 requires a ProducerTask");
        }
        try {
            resolvePlan();
            buildRings();
            startRings();
            state = State.RUNNING;
            if (spec.producersPerPartition() > 0) {
                startProducers(producerTask);
            }
        } catch (final RuntimeException | Error e) {
            haltAll();
            state = State.STOPPED;
            throw e;
        }
    }

    private void resolvePlan() {
        if (policy == PlacementPolicy.NONE) {
            plan = null;
            return;
        }
        if (!affinity.isSupported()) {
            throw new PlacementException("placement policy " + policy + " needs CPU pinning, which is unsupported here;"
                + " use PlacementPolicy.NONE or supply a CpuAffinity");
        }
        if (policy == PlacementPolicy.EXPLICIT) {
            final CommunicationGraph graph = WorkloadGraphBuilder.build(spec);
            final List<String> violations = explicitPlan.validate(graph, topology, constraints);
            if (!violations.isEmpty()) {
                throw new PlacementException("explicit placement plan is invalid: " + violations);
            }
            plan = explicitPlan;
            return;
        }
        final PlacementManager.Planned planned = new PlacementManager(topology, costModel, constraints).plan(spec, policy);
        plan = planned.plan();
        optimizerNanos = planned.optimizerNanos();
    }

    private void buildRings() {
        for (int p = 0; p < spec.partitions(); p++) {
            final List<NumaDisruptorFactory.Ring<E>> perPartition = new ArrayList<>();
            for (int r = 0; r < spec.rings(); r++) {
                final boolean last = r == spec.forwardingStages();
                final ProducerType type = r == 0 && spec.ring0IsMultiProducer() ? ProducerType.MULTI : ProducerType.SINGLE;
                final int handlers = last ? spec.consumersPerPartition() : 1;
                List<Integer> cpus = null;
                BitSet constructOn = null;
                if (plan != null) {
                    cpus = new ArrayList<>();
                    if (last) {
                        for (int c = 0; c < handlers; c++) {
                            cpus.add(plan.cpuOf(VertexIds.consumer(p, c)));
                        }
                    } else {
                        cpus.add(plan.cpuOf(VertexIds.forwarder(p, r)));
                    }
                    if (ringInit == RingInit.ON_PLANNED_NODE) {
                        constructOn = new BitSet();
                        topology.allowedCpusOnNode(plan.nodeOf(VertexIds.ring(p, r))).forEach(constructOn::set);
                    }
                }
                final NumaDisruptorFactory.Ring<E> ring = NumaDisruptorFactory.create(
                    String.format("p%d-r%d", p, r), eventFactory, spec.ringBufferSize(), type, waitKind.create(),
                    affinity, constructOn, cpus, handlers);
                ring.disruptor().setDefaultExceptionHandler(new FailureRecorder());
                perPartition.add(ring);
            }
            rings.add(perPartition);
        }
        // handlers are registered after all rings exist because a forwarder needs its target ring
        for (int p = 0; p < spec.partitions(); p++) {
            final int partition = p;
            for (int r = 0; r < spec.rings(); r++) {
                final var ring = rings.get(p).get(r);
                if (r < spec.forwardingStages()) {
                    final RingBuffer<E> target = rings.get(p).get(r + 1).disruptor().getRingBuffer();
                    ring.disruptor().handleEventsWith(new CrossNodeBridge<>(target, stageFunction, partition, () -> aborting));
                } else {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    final EventHandler<E>[] consumers = new EventHandler[spec.consumersPerPartition()];
                    for (int c = 0; c < consumers.length; c++) {
                        final EventHandler<E> user = consumerFactory.create(partition, c);
                        consumers[c] = spec.mode() == ConsumptionMode.WORK_DISTRIBUTION && consumers.length > 1
                            ? new WorkDistributionHandler<>(user, c, consumers.length) : user;
                    }
                    ring.disruptor().handleEventsWith(consumers);
                }
            }
        }
    }

    /** Start consumers before producers, downstream rings before upstream ones. */
    private void startRings() {
        for (int r = spec.rings() - 1; r >= 0; r--) {
            for (int p = 0; p < spec.partitions(); p++) {
                rings.get(p).get(r).disruptor().start();
            }
        }
        final long deadline = System.nanoTime() + startTimeout.toNanos();
        for (final var perPartition : rings) {
            for (final var ring : perPartition) {
                if (ring.pinned() == null) {
                    continue;
                }
                while (ring.pinned().pinnedCount() < ring.handlerCount() && ring.pinned().failures().isEmpty()) {
                    if (System.nanoTime() > deadline) {
                        throw new PlacementException("worker threads did not pin within " + startTimeout);
                    }
                    Thread.onSpinWait();
                }
                if (!ring.pinned().failures().isEmpty()) {
                    throw new PlacementException("pinning a consumer thread failed", ring.pinned().failures().get(0));
                }
            }
        }
    }

    private void startProducers(final ProducerTask<E> task) {
        final List<RingBuffer<E>> rings0 = new ArrayList<>();
        for (final var perPartition : rings) {
            rings0.add(perPartition.get(0).disruptor().getRingBuffer());
        }
        int[] cpus = null;
        if (plan != null) {
            cpus = new int[spec.partitions() * spec.producersPerPartition()];
            int i = 0;
            for (int p = 0; p < spec.partitions(); p++) {
                for (int k = 0; k < spec.producersPerPartition(); k++) {
                    cpus[i++] = plan.cpuOf(VertexIds.producer(p, k));
                }
            }
        }
        scheduler = new ProducerScheduler<>(spec, rings0, cpus, affinity, this::producingAllowed);
        try {
            scheduler.start(task);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlacementException("interrupted while starting producers", e);
        }
    }

    private boolean producingAllowed() {
        return state == State.RUNNING && failures.isEmpty();
    }

    /** True while the processor accepts new events (running and no worker has failed). */
    public boolean isAcceptingEvents() {
        return producingAllowed();
    }

    /** Wait for the framework producer tasks to return. @return false if they did not within {@code timeout} */
    public boolean awaitProducers(final Duration timeout) throws InterruptedException {
        return scheduler == null || scheduler.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Stop producers, drain every ring in pipeline order, then halt all workers.
     *
     * @throws ProcessorFailedException if any worker failed, producers or the drain did not finish within
     *         {@code timeout}; the processor is fully halted either way
     */
    public synchronized void stop(final Duration timeout) {
        if (state == State.NEW || state == State.STOPPED) {
            state = State.STOPPED;
            return;
        }
        state = State.STOPPING;
        final long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (inFlightPublishers.sum() != 0) { // external publishers past the RUNNING check; they finish in bounded time
                if (System.nanoTime() > deadline) {
                    failures.add(new java.util.concurrent.TimeoutException("external publishers did not leave within " + timeout));
                    break;
                }
                Thread.onSpinWait();
            }
            if (scheduler != null) {
                if (!scheduler.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    failures.add(new java.util.concurrent.TimeoutException("producer tasks did not return within " + timeout));
                    scheduler.interruptAll();
                }
                failures.addAll(scheduler.failures());
            }
            drain:
            for (int r = 0; r < spec.rings(); r++) {
                for (int p = 0; p < spec.partitions(); p++) {
                    final long left = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    try {
                        rings.get(p).get(r).disruptor().shutdown(left, TimeUnit.MILLISECONDS);
                    } catch (final TimeoutException e) {
                        failures.add(new java.util.concurrent.TimeoutException(
                            "ring " + r + " of partition " + p + " did not drain within " + timeout));
                        break drain;
                    }
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add(e);
        } finally {
            haltAll();
            state = State.STOPPED;
        }
        if (!failures.isEmpty()) {
            throw new ProcessorFailedException(failures.size() + " failure(s); first: " + failures.get(0), failures.get(0));
        }
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(30));
    }

    private void haltAll() {
        aborting = true;
        for (final var perPartition : rings) {
            for (final var ring : perPartition) {
                try {
                    ring.disruptor().halt();
                } catch (final RuntimeException ignored) {
                    // halting a ring that never started is fine
                }
            }
        }
    }

    // ------------------------------------------------------------------ external publishing

    /**
     * Non-blocking publish from any application thread (requires {@code externalPublishers(true)}).
     *
     * @return false if the target ring is full (backpressure); nothing was published
     */
    public <A> boolean tryPublish(final long routingKey, final EventTranslatorOneArg<E, A> translator, final A arg) {
        if (!spec.externalPublishers()) {
            throw new IllegalStateException("externalPublishers(true) was not configured");
        }
        // Register as in flight BEFORE checking the state: stop() flips the state and then waits for this counter to reach
        // zero, so a publisher either sees the state change or is waited for; an accepted event is always drained.
        inFlightPublishers.increment();
        try {
            if (state != State.RUNNING) {
                throw new IllegalStateException("processor is not running: " + state);
            }
            final RingBuffer<E> ring = rings.get(router.route(routingKey, spec.partitions())).get(0).disruptor().getRingBuffer();
            if (ring.tryPublishEvent(translator, arg)) {
                return true;
            }
            externalStalls.increment();
            return false;
        } finally {
            inFlightPublishers.decrement();
        }
    }

    /**
     * Blocking publish with bounded waiting: spins while the ring is full but throws as soon as the processor stops or fails.
     */
    public <A> void publish(final long routingKey, final EventTranslatorOneArg<E, A> translator, final A arg) {
        while (!tryPublish(routingKey, translator, arg)) {
            if (!producingAllowed()) {
                throw new IllegalStateException("processor stopped or failed while publishing");
            }
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ introspection

    public WorkloadSpec spec() { return spec; }

    public NumaTopology topology() { return topology; }

    /** The resolved plan, empty for {@link PlacementPolicy#NONE} or before start. */
    public Optional<PlacementPlan> placementPlan() { return Optional.ofNullable(plan); }

    /** Time the optimizer took in {@link #start}, in nanoseconds (0 for NONE and EXPLICIT). */
    public long optimizerNanos() { return optimizerNanos; }

    public RingBuffer<E> ringBuffer(final int partition, final int ring) {
        return rings.get(partition).get(ring).disruptor().getRingBuffer();
    }

    public List<ProducerContext<E>> producerContexts() {
        return scheduler == null ? List.of() : scheduler.contexts();
    }

    public List<Throwable> failures() { return List.copyOf(failures); }

    public Stats stats() {
        return new Stats(scheduler == null ? 0 : scheduler.totalStalls(), externalStalls.sum(), failedEvents.get(),
            scheduler == null ? 0 : scheduler.totalPublished(), scheduler == null ? 0 : scheduler.totalStallEpisodes());
    }

    /** Human-readable placement, one line per vertex. */
    public String describePlacement() {
        if (plan == null) {
            return "placement: none (OS scheduling)";
        }
        final StringBuilder sb = new StringBuilder("placement (" + plan.optimizerName() + ", model cost " + plan.modelCost() + ")\n");
        plan.nodes().forEach((v, n) -> sb.append("  ").append(v).append(" -> node ").append(n)
            .append(plan.hasCpu(v) ? " cpu " + plan.cpuOf(v) : "").append('\n'));
        plan.warnings().forEach(w -> sb.append("  WARNING: ").append(w).append('\n'));
        return sb.toString();
    }

    /** Records the first failure of any handler/stage and lets the pipeline keep draining. */
    private final class FailureRecorder implements ExceptionHandler<E> {
        @Override
        public void handleEventException(final Throwable ex, final long sequence, final E event) {
            if (ex instanceof CrossNodeBridge.BridgeStoppedException) {
                return;
            }
            failedEvents.incrementAndGet();
            failures.add(ex);
        }

        @Override
        public void handleOnStartException(final Throwable ex) {
            failures.add(ex);
        }

        @Override
        public void handleOnShutdownException(final Throwable ex) {
            failures.add(ex);
        }
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder<E> {
        private EventFactory<E> eventFactory;
        private ConsumerFactory<E> consumerFactory;
        private StageFunction<E> stageFunction;
        private int partitions = 1;
        private int producersPerPartition = 1;
        private int forwardingStages;
        private int consumersPerPartition = 1;
        private ConsumptionMode mode = ConsumptionMode.MULTICAST;
        private int ringBufferSize = 1 << 16;
        private boolean externalPublishers;
        private double eventsPerSecondPerPartition = 1_000_000;
        private int payloadBytes = 64;
        private int consumerBatch = 8;
        private WaitStrategyKind waitKind = WaitStrategyKind.YIELDING;
        private PlacementPolicy policy = PlacementPolicy.MIN_REMOTE_TRAFFIC;
        private PlacementPlan explicitPlan;
        private NumaTopology topology;
        private CpuAffinity affinity;
        private PlacementConstraints constraints = PlacementConstraints.defaults();
        private PlacementCostModel costModel;
        private EventRouter router = EventRouter.hashing();
        private Duration startTimeout = Duration.ofSeconds(10);
        private RingInit ringInit = RingInit.ON_PLANNED_NODE;

        public Builder<E> eventFactory(final EventFactory<E> f) { this.eventFactory = f; return this; }

        /** Creates the final-stage handler of each consumer slot. */
        public Builder<E> consumers(final int perPartition, final ConsumptionMode mode, final ConsumerFactory<E> factory) {
            this.consumersPerPartition = perPartition;
            this.mode = mode;
            this.consumerFactory = factory;
            return this;
        }

        /** Insert {@code count} single-threaded 1:1 forwarding stages (each with its own ring) before the consumers. */
        public Builder<E> forwardingStages(final int count, final StageFunction<E> function) {
            this.forwardingStages = count;
            this.stageFunction = function;
            return this;
        }

        public Builder<E> partitions(final int n) { this.partitions = n; return this; }

        public Builder<E> producersPerPartition(final int n) { this.producersPerPartition = n; return this; }

        /** Allow arbitrary application threads to call {@code publish}; makes ring 0 multi-producer. */
        public Builder<E> externalPublishers(final boolean b) { this.externalPublishers = b; return this; }

        public Builder<E> ringBufferSize(final int n) { this.ringBufferSize = n; return this; }

        public Builder<E> waitStrategy(final WaitStrategyKind k) { this.waitKind = k; return this; }

        public Builder<E> placementPolicy(final PlacementPolicy p) { this.policy = p; return this; }

        /** Use exactly this plan; implies {@link PlacementPolicy#EXPLICIT}. */
        public Builder<E> placementPlan(final PlacementPlan plan) {
            this.explicitPlan = plan;
            this.policy = PlacementPolicy.EXPLICIT;
            return this;
        }

        public Builder<E> topology(final NumaTopology t) { this.topology = t; return this; }

        public Builder<E> affinity(final CpuAffinity a) { this.affinity = a; return this; }

        public Builder<E> constraints(final PlacementConstraints c) { this.constraints = c; return this; }

        public Builder<E> costModel(final PlacementCostModel m) { this.costModel = m; return this; }

        public Builder<E> router(final EventRouter r) { this.router = r; return this; }

        /** Traffic estimates fed to the optimizer; they do not limit the actual rate. */
        public Builder<E> trafficEstimate(final double eventsPerSecondPerPartition, final int payloadBytes, final int consumerBatch) {
            this.eventsPerSecondPerPartition = eventsPerSecondPerPartition;
            this.payloadBytes = payloadBytes;
            this.consumerBatch = consumerBatch;
            return this;
        }

        public Builder<E> startTimeout(final Duration d) { this.startTimeout = d; return this; }

        /** Where ring memory is first touched; see {@link RingInit}. */
        public Builder<E> ringInit(final RingInit mode) { this.ringInit = mode; return this; }

        public NumaEventProcessor<E> build() {
            return new NumaEventProcessor<>(this);
        }
    }
}
