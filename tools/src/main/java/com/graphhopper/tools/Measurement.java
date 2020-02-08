/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.*;
import com.graphhopper.coll.GHBitSet;
import com.graphhopper.coll.GHBitSetImpl;
import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.weighting.CustomWeighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.*;
import com.graphhopper.util.Parameters.Algorithms;
import com.graphhopper.util.Parameters.CH;
import com.graphhopper.util.Parameters.Landmark;
import com.graphhopper.util.shapes.BBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.graphhopper.util.Helper.*;
import static com.graphhopper.util.Parameters.Algorithms.ALT_ROUTE;
import static com.graphhopper.util.Parameters.Algorithms.DIJKSTRA_BI;
import static com.graphhopper.util.Parameters.Routing.BLOCK_AREA;

/**
 * @author Peter Karich
 */
public class Measurement {
    private static final Logger logger = LoggerFactory.getLogger(Measurement.class);
    private final Map<String, Object> properties = new TreeMap<>();
    private long seed;
    private int maxNode;

    public static void main(String[] strs) throws IOException {
        PMap args = PMap.read(strs);
        int repeats = args.getInt("measurement.repeats", 1);
        for (int i = 0; i < repeats; ++i)
            new Measurement().start(args);
    }

    // creates properties file in the format key=value
    // Every value is one y-value in a separate diagram with an identical x-value for every Measurement.start call
    void start(PMap args) throws IOException {
        final String graphLocation = args.get("graph.location", "");
        final boolean useJson = args.getBool("measurement.json", false);
        boolean cleanGraph = args.getBool("measurement.clean", false);
        String summaryLocation = args.get("measurement.summaryfile", "");
        final String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date());
        put("measurement.timestamp", timeStamp);
        String propFolder = args.get("measurement.folder", "");
        if (!propFolder.isEmpty()) {
            Files.createDirectories(Paths.get(propFolder));
        }
        String propFilename = args.get("measurement.filename", "");
        if (isEmpty(propFilename)) {
            if (useJson) {
                // if we start from IDE or otherwise jar was not built using maven the git commit id will be unknown
                String id = Constants.GIT_INFO != null ? Constants.GIT_INFO.getCommitHash().substring(0, 8) : "unknown";
                propFilename = "measurement_" + id + "_" + timeStamp + ".json";
            } else {
                propFilename = "measurement_" + timeStamp + ".properties";
            }
        }
        final String propLocation = Paths.get(propFolder).resolve(propFilename).toString();
        seed = args.getLong("measurement.seed", 123);
        put("measurement.gitinfo", args.get("measurement.gitinfo", ""));
        int count = args.getInt("measurement.count", 5000);
        put("measurement.map", args.get("datareader.file", "unknown"));

        final boolean useMeasurementTimeAsRefTime = args.getBool("measurement.use_measurement_time_as_ref_time", false);
        if (useMeasurementTimeAsRefTime && !useJson) {
            throw new IllegalArgumentException("Using measurement time as reference time only works with json files");
        }

        GraphHopper hopper = new GraphHopperOSM() {
            @Override
            protected void prepareCH(boolean closeEarly) {
                StopWatch sw = new StopWatch().start();
                super.prepareCH(closeEarly);
                // note that we measure the total time of all (possibly edge&node) CH preparations
                put(Parameters.CH.PREPARE + "time", sw.stop().getMillis());
                int edges = getGraphHopperStorage().getEdges();
                if (!getCHFactoryDecorator().getNodeBasedCHProfiles().isEmpty()) {
                    CHProfile chProfile = getCHFactoryDecorator().getNodeBasedCHProfiles().get(0);
                    int edgesAndShortcuts = getGraphHopperStorage().getCHGraph(chProfile).getEdges();
                    put(Parameters.CH.PREPARE + "node.shortcuts", edgesAndShortcuts - edges);
                    put(Parameters.CH.PREPARE + "node.time", getCHFactoryDecorator().getPreparation(chProfile).getTotalPrepareTime());
                }
                if (!getCHFactoryDecorator().getEdgeBasedCHProfiles().isEmpty()) {
                    CHProfile chProfile = getCHFactoryDecorator().getEdgeBasedCHProfiles().get(0);
                    int edgesAndShortcuts = getGraphHopperStorage().getCHGraph(chProfile).getEdges();
                    put(Parameters.CH.PREPARE + "edge.shortcuts", edgesAndShortcuts - edges);
                    put(Parameters.CH.PREPARE + "edge.time", getCHFactoryDecorator().getPreparation(chProfile).getTotalPrepareTime());
                }
            }

            @Override
            protected void loadOrPrepareLM(boolean closeEarly) {
                StopWatch sw = new StopWatch().start();
                super.loadOrPrepareLM(closeEarly);
                put(Landmark.PREPARE + "time", sw.stop().getMillis());
            }

            @Override
            protected DataReader importData() throws IOException {
                StopWatch sw = new StopWatch().start();
                DataReader dr = super.importData();
                put("graph.import_time", sw.stop().getSeconds());
                return dr;
            }
        };

        CustomModel customModel = createCustomModel();
        hopper.putCustomModel(customModel.toString(), customModel);

        // add more encoded values for CustomModel
        args.put("graph.encoded_values", "max_width,max_height,toll,hazmat");
        hopper.init(new GraphHopperConfig(args)).
                // use server to allow path simplification
                        forServer();
        if (cleanGraph) {
            hopper.clean();
        }

        hopper.getCHFactoryDecorator().setDisablingAllowed(true);
        hopper.getLMFactoryDecorator().setDisablingAllowed(true);
        hopper.importOrLoad();

        GraphHopperStorage g = hopper.getGraphHopperStorage();
        EncodingManager encodingManager = hopper.getEncodingManager();
        if (encodingManager.fetchEdgeEncoders().size() != 1) {
            throw new IllegalArgumentException("There has to be exactly one encoder for each measurement");
        }
        FlagEncoder encoder = encodingManager.fetchEdgeEncoders().get(0);
        String vehicleStr = encoder.toString();

        StopWatch sw = new StopWatch().start();
        try {
            maxNode = g.getNodes();
            boolean isCH = false;
            boolean isLM = false;
            final boolean runSlow = args.getBool("measurement.run_slow_routing", true);
            GHBitSet allowedEdges = printGraphDetails(g, vehicleStr);
            printMiscUnitPerfTests(g, isCH, encoder, count * 100, allowedEdges);
            printLocationIndexQuery(g, hopper.getLocationIndex(), count);
            String blockAreaStr = "49.394664,11.144428,49.348388,11.144943,49.355768,11.227169,49.411643,11.227512";
            if (runSlow) {
                printTimeOfRouteQuery(hopper, new QuerySettings("routing", vehicleStr, count / 20, isCH, isLM).
                        withInstructions());
                printTimeOfRouteQuery(hopper, new QuerySettings("routing_edge", vehicleStr, count / 30, isCH, isLM).
                        withInstructions().edgeBased());
                printTimeOfRouteQuery(hopper, new QuerySettings("routing_custom", customModel.toString(), count / 30, isCH, isLM).
                        withInstructions().customModel(customModel));
                printTimeOfRouteQuery(hopper, new QuerySettings("routing_block_area", vehicleStr, count / 30, isCH, isLM).
                        withInstructions().blockArea(blockAreaStr));
            }

            if (hopper.getLMFactoryDecorator().isEnabled()) {
                System.gc();
                isLM = true;
                for (int activeLMCount : Arrays.asList(4, 8, 12, 16)) {
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingLM" + activeLMCount, vehicleStr, count / 4, isCH, isLM).
                            withInstructions().activeLandmarks(activeLMCount));
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingLM" + activeLMCount + "_edge", vehicleStr, count / 4, isCH, isLM).
                            withInstructions().activeLandmarks(activeLMCount).edgeBased());
                }

                final int activeLMCount = 8;
                printTimeOfRouteQuery(hopper, new QuerySettings("routingLM" + activeLMCount + "_block_area", vehicleStr, count / 4, isCH, isLM).
                        withInstructions().activeLandmarks(activeLMCount).blockArea(blockAreaStr));
                printTimeOfRouteQuery(hopper, new QuerySettings("routingLM" + activeLMCount + "_custom", customModel.toString(), count / 5, isCH, isLM).
                        withInstructions().activeLandmarks(activeLMCount).customModel(customModel));
                // compareRouting(hopper, vehicleStr, count / 5);
            }

            if (hopper.getCHFactoryDecorator().isEnabled()) {
                isCH = true;
//                compareCHWithAndWithoutSOD(hopper, vehicleStr, count/5);
                if (hopper.getLMFactoryDecorator().isEnabled()) {
                    isLM = true;
                    System.gc();
                    // try just one constellation, often ~4-6 is best
                    int lmCount = 5;
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCHLM" + lmCount, vehicleStr, count, isCH, isLM).
                            withInstructions().activeLandmarks(lmCount).sod());
                }

                isLM = false;
                System.gc();
                if (!hopper.getCHFactoryDecorator().getNodeBasedCHProfiles().isEmpty()) {
                    CHProfile chProfile = hopper.getCHFactoryDecorator().getNodeBasedCHProfiles().get(0);
                    CHGraph lg = g.getCHGraph(chProfile);
                    fillAllowedEdges(lg.getAllEdges(), allowedEdges);
                    printMiscUnitPerfTests(lg, isCH, encoder, count * 100, allowedEdges);
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH", vehicleStr, count, isCH, isLM).
                            withInstructions().sod());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_alt", vehicleStr, count / 10, isCH, isLM).
                            withInstructions().sod().alternative());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_with_hints", vehicleStr, count, isCH, isLM).
                            withInstructions().sod().withPointHints());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_no_sod", vehicleStr, count, isCH, isLM).
                            withInstructions());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_no_instr", vehicleStr, count, isCH, isLM).
                            sod());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_full", vehicleStr, count, isCH, isLM).
                            withInstructions().withPointHints().sod().simplify());
                }
                if (!hopper.getCHFactoryDecorator().getEdgeBasedCHProfiles().isEmpty()) {
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_edge", vehicleStr, count, isCH, isLM).
                            edgeBased().withInstructions());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_edge_no_instr", vehicleStr, count, isCH, isLM).
                            edgeBased());
                    printTimeOfRouteQuery(hopper, new QuerySettings("routingCH_edge_full", vehicleStr, count, isCH, isLM).
                            edgeBased().withInstructions().withPointHints().simplify());
                }
            }
        } catch (Exception ex) {
            logger.error("Problem while measuring " + graphLocation, ex);
            put("error", ex.toString());
        } finally {
            put("gh.gitinfo", Constants.GIT_INFO != null ? Constants.GIT_INFO.toString() : "unknown");
            put("measurement.count", count);
            put("measurement.seed", seed);
            put("measurement.time", sw.stop().getMillis());
            System.gc();
            put("measurement.totalMB", getTotalMB());
            put("measurement.usedMB", getUsedMB());

            if (!isEmpty(summaryLocation)) {
                writeSummary(summaryLocation, propLocation);
            }
            if (useJson) {
                storeJson(propLocation, useMeasurementTimeAsRefTime);
            } else {
                storeProperties(propLocation);
            }
        }
    }

    private static class QuerySettings {
        private final String prefix, vehicle;
        private final int count;
        final boolean ch, lm;
        int activeLandmarks = -1;
        boolean withInstructions, withPointHints, sod, edgeBased, simplify, alternative;
        String blockArea;
        CustomModel customModel;

        QuerySettings(String prefix, String vehicle, int count, boolean isCH, boolean isLM) {
            this.vehicle = vehicle;
            this.prefix = prefix;
            this.count = count;
            this.ch = isCH;
            this.lm = isLM;
        }

        QuerySettings withInstructions() {
            this.withInstructions = true;
            return this;
        }

        QuerySettings withPointHints() {
            this.withPointHints = true;
            return this;
        }

        QuerySettings sod() {
            sod = true;
            return this;
        }

        QuerySettings activeLandmarks(int alm) {
            this.activeLandmarks = alm;
            return this;
        }

        QuerySettings edgeBased() {
            this.edgeBased = true;
            return this;
        }

        QuerySettings simplify() {
            this.simplify = true;
            return this;
        }

        QuerySettings alternative() {
            alternative = true;
            return this;
        }

        QuerySettings blockArea(String str) {
            blockArea = str;
            return this;
        }

        public QuerySettings customModel(CustomModel customModel) {
            this.customModel = customModel;
            return this;
        }

        public CustomModel customModel() {
            return customModel;
        }
    }

    void fillAllowedEdges(AllEdgesIterator iter, GHBitSet bs) {
        bs.clear();
        while (iter.next()) {
            bs.add(iter.getEdge());
        }
    }

    private GHBitSet printGraphDetails(GraphHopperStorage g, String vehicleStr) {
        // graph size (edge, node and storage size)
        put("graph.nodes", g.getNodes());
        put("graph.edges", g.getAllEdges().length());
        put("graph.size_in_MB", g.getCapacity() / MB);
        put("graph.encoder", vehicleStr);

        AllEdgesIterator iter = g.getAllEdges();
        final int maxEdgesId = g.getAllEdges().length();
        final GHBitSet allowedEdges = new GHBitSetImpl(maxEdgesId);
        fillAllowedEdges(iter, allowedEdges);
        put("graph.valid_edges", allowedEdges.getCardinality());
        return allowedEdges;
    }

    private void printLocationIndexQuery(Graph g, final LocationIndex idx, int count) {
        count *= 2;
        final BBox bbox = g.getBounds();
        final double latDelta = bbox.maxLat - bbox.minLat;
        final double lonDelta = bbox.maxLon - bbox.minLon;
        final Random rand = new Random(seed);
        MiniPerfTest miniPerf = new MiniPerfTest() {
            @Override
            public int doCalc(boolean warmup, int run) {
                double lat = rand.nextDouble() * latDelta + bbox.minLat;
                double lon = rand.nextDouble() * lonDelta + bbox.minLon;
                int val = idx.findClosest(lat, lon, EdgeFilter.ALL_EDGES).getClosestNode();
//                if (!warmup && val >= 0)
//                    list.add(val);

                return val;
            }
        }.setIterations(count).start();

        print("location_index", miniPerf);
    }

    private void printMiscUnitPerfTests(final Graph graph, boolean isCH, final FlagEncoder encoder,
                                        int count, final GHBitSet allowedEdges) {
        final Random rand = new Random(seed);
        String description = "";
        if (isCH) {
            description = "CH";
            CHGraph lg = (CHGraph) graph;
            final CHEdgeExplorer chExplorer = lg.createEdgeExplorer(new LevelEdgeFilter(lg));
            MiniPerfTest miniPerf = new MiniPerfTest() {
                @Override
                public int doCalc(boolean warmup, int run) {
                    int nodeId = rand.nextInt(maxNode);
                    return GHUtility.count(chExplorer.setBaseNode(nodeId));
                }
            }.setIterations(count).start();
            print("unit_testsCH.level_edge_state_next", miniPerf);

            final CHEdgeExplorer chExplorer2 = lg.createEdgeExplorer();
            miniPerf = new MiniPerfTest() {
                @Override
                public int doCalc(boolean warmup, int run) {
                    int nodeId = rand.nextInt(maxNode);
                    CHEdgeIterator iter = chExplorer2.setBaseNode(nodeId);
                    while (iter.next()) {
                        if (iter.isShortcut())
                            nodeId += (int) iter.getWeight();
                    }
                    return nodeId;
                }
            }.setIterations(count).start();
            print("unit_testsCH.get_weight", miniPerf);
        }

        EdgeFilter outFilter = DefaultEdgeFilter.outEdges(encoder);
        final EdgeExplorer outExplorer = graph.createEdgeExplorer(outFilter);
        MiniPerfTest miniPerf = new MiniPerfTest() {
            @Override
            public int doCalc(boolean warmup, int run) {
                int nodeId = rand.nextInt(maxNode);
                return GHUtility.count(outExplorer.setBaseNode(nodeId));
            }
        }.setIterations(count).start();
        print("unit_tests" + description + ".out_edge_state_next", miniPerf);

        final EdgeExplorer allExplorer = graph.createEdgeExplorer();
        miniPerf = new MiniPerfTest() {
            @Override
            public int doCalc(boolean warmup, int run) {
                int nodeId = rand.nextInt(maxNode);
                return GHUtility.count(allExplorer.setBaseNode(nodeId));
            }
        }.setIterations(count).start();
        print("unit_tests" + description + ".all_edge_state_next", miniPerf);

        final int maxEdgesId = graph.getAllEdges().length();
        miniPerf = new MiniPerfTest() {
            @Override
            public int doCalc(boolean warmup, int run) {
                while (true) {
                    int edgeId = rand.nextInt(maxEdgesId);
                    if (allowedEdges.contains(edgeId))
                        return graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE).getEdge();
                }
            }
        }.setIterations(count).start();
        print("unit_tests" + description + ".get_edge_state", miniPerf);
    }

    private void compareRouting(final GraphHopper hopper, String vehicle, int count) {
        logger.info("Comparing " + count + " routes. Differences will be printed to stderr.");
        String algo = Algorithms.ASTAR_BI;
        final Random rand = new Random(seed);
        final Graph g = hopper.getGraphHopperStorage();
        final NodeAccess na = g.getNodeAccess();

        for (int i = 0; i < count; i++) {
            int from = rand.nextInt(maxNode);
            int to = rand.nextInt(maxNode);

            double fromLat = na.getLatitude(from);
            double fromLon = na.getLongitude(from);
            double toLat = na.getLatitude(to);
            double toLon = na.getLongitude(to);
            GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon).
                    setWeighting("fastest").
                    setVehicle(vehicle).
                    setAlgorithm(algo);

            GHResponse lmRsp = hopper.route(req);
            req.getHints().put(Landmark.DISABLE, true);
            GHResponse originalRsp = hopper.route(req);

            String locStr = " iteration " + i + ". " + fromLat + "," + fromLon + " -> " + toLat + "," + toLon;
            if (lmRsp.hasErrors()) {
                if (originalRsp.hasErrors())
                    continue;
                logger.error("Error for LM but not for original response " + locStr);
            }

            String infoStr = " weight:" + lmRsp.getBest().getRouteWeight() + ", original: " + originalRsp.getBest().getRouteWeight()
                    + " distance:" + lmRsp.getBest().getDistance() + ", original: " + originalRsp.getBest().getDistance()
                    + " time:" + round2(lmRsp.getBest().getTime() / 1000) + ", original: " + round2(originalRsp.getBest().getTime() / 1000)
                    + " points:" + lmRsp.getBest().getPoints().size() + ", original: " + originalRsp.getBest().getPoints().size();

            if (Math.abs(1 - lmRsp.getBest().getRouteWeight() / originalRsp.getBest().getRouteWeight()) > 0.000001)
                logger.error("Too big weight difference for LM. " + locStr + infoStr);
        }
    }

    private void compareCHWithAndWithoutSOD(final GraphHopper hopper, String vehicle, int count) {
        logger.info("Comparing " + count + " routes for CH with and without stall on demand." +
                " Differences will be printed to stderr.");
        final Random rand = new Random(seed);
        final Graph g = hopper.getGraphHopperStorage();
        final NodeAccess na = g.getNodeAccess();

        for (int i = 0; i < count; i++) {
            int from = rand.nextInt(maxNode);
            int to = rand.nextInt(maxNode);

            double fromLat = na.getLatitude(from);
            double fromLon = na.getLongitude(from);
            double toLat = na.getLatitude(to);
            double toLon = na.getLongitude(to);
            GHRequest sodReq = new GHRequest(fromLat, fromLon, toLat, toLon).
                    setWeighting("fastest").
                    setVehicle(vehicle).
                    setAlgorithm(DIJKSTRA_BI);

            GHRequest noSodReq = new GHRequest(fromLat, fromLon, toLat, toLon).
                    setWeighting("fastest").
                    setVehicle(vehicle).
                    setAlgorithm(DIJKSTRA_BI);
            noSodReq.getHints().put("stall_on_demand", false);

            GHResponse sodRsp = hopper.route(sodReq);
            GHResponse noSodRsp = hopper.route(noSodReq);

            String locStr = " iteration " + i + ". " + fromLat + "," + fromLon + " -> " + toLat + "," + toLon;
            if (sodRsp.hasErrors()) {
                if (noSodRsp.hasErrors()) {
                    logger.info("Error with and without SOD");
                    continue;
                } else {
                    logger.error("Error with SOD but not without SOD" + locStr);
                    continue;
                }
            }
            String infoStr =
                    " weight:" + noSodRsp.getBest().getRouteWeight() + ", original: " + sodRsp.getBest().getRouteWeight()
                            + " distance:" + noSodRsp.getBest().getDistance() + ", original: " + sodRsp.getBest().getDistance()
                            + " time:" + round2(noSodRsp.getBest().getTime() / 1000) + ", original: " + round2(sodRsp.getBest().getTime() / 1000)
                            + " points:" + noSodRsp.getBest().getPoints().size() + ", original: " + sodRsp.getBest().getPoints().size();

            if (Math.abs(1 - noSodRsp.getBest().getRouteWeight() / sodRsp.getBest().getRouteWeight()) > 0.000001)
                logger.error("Too big weight difference for SOD. " + locStr + infoStr);
        }
    }

    private void printTimeOfRouteQuery(final GraphHopper hopper, final QuerySettings querySettings) {
        final Graph g = hopper.getGraphHopperStorage();
        final AtomicLong maxDistance = new AtomicLong(0);
        final AtomicLong minDistance = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong distSum = new AtomicLong(0);
        final AtomicLong airDistSum = new AtomicLong(0);
        final AtomicLong altCount = new AtomicLong(0);
        final AtomicInteger failedCount = new AtomicInteger(0);
        final DistanceCalc distCalc = new DistanceCalcEarth();
        final AtomicLong visitedNodesSum = new AtomicLong(0);
        final AtomicLong maxVisitedNodes = new AtomicLong(0);
//        final AtomicLong extractTimeSum = new AtomicLong(0);
//        final AtomicLong calcPointsTimeSum = new AtomicLong(0);
//        final AtomicLong calcDistTimeSum = new AtomicLong(0);
//        final AtomicLong tmpDist = new AtomicLong(0);
        final Random rand = new Random(seed);
        final NodeAccess na = g.getNodeAccess();

        MiniPerfTest miniPerf = new MiniPerfTest() {
            EdgeExplorer edgeExplorer;

            @Override
            public int doCalc(boolean warmup, int run) {
                int from = rand.nextInt(maxNode);
                int to = rand.nextInt(maxNode);
                double fromLat = na.getLatitude(from);
                double fromLon = na.getLongitude(from);
                double toLat = na.getLatitude(to);
                double toLon = na.getLongitude(to);
                GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon);
                if (querySettings.customModel == null)
                    req.setWeighting("fastest").setVehicle(querySettings.vehicle);
                else
                    req.setWeighting(CustomWeighting.key(querySettings.vehicle));

                req.getHints().put(CH.DISABLE, !querySettings.ch).
                        put("stall_on_demand", querySettings.sod).
                        put(Parameters.Routing.EDGE_BASED, querySettings.edgeBased).
                        put(Landmark.DISABLE, !querySettings.lm).
                        put(Landmark.ACTIVE_COUNT, querySettings.activeLandmarks).
                        put("instructions", querySettings.withInstructions);

                if (querySettings.alternative)
                    req.setAlgorithm(ALT_ROUTE);

                if (querySettings.withInstructions)
                    req.setPathDetails(Arrays.asList(Parameters.Details.AVERAGE_SPEED));

                if (querySettings.simplify) {
                    req.setPathDetails(Arrays.asList(Parameters.Details.AVERAGE_SPEED, Parameters.Details.EDGE_ID, Parameters.Details.STREET_NAME));
                } else {
                    // disable path simplification by setting the distance to zero
                    req.getHints().put(Parameters.Routing.WAY_POINT_MAX_DISTANCE, 0);
                }

                if (querySettings.blockArea != null)
                    req.getHints().put(BLOCK_AREA, querySettings.blockArea);

                if (querySettings.withPointHints) {
                    if (edgeExplorer == null)
                        edgeExplorer = g.createEdgeExplorer(DefaultEdgeFilter.allEdges(hopper.getEncodingManager().getEncoder(querySettings.vehicle)));
                    EdgeIterator iter = edgeExplorer.setBaseNode(from);
                    if (!iter.next())
                        throw new IllegalArgumentException("wrong 'from' when adding point hint");
                    EdgeIterator iter2 = edgeExplorer.setBaseNode(to);
                    if (!iter2.next())
                        throw new IllegalArgumentException("wrong 'to' when adding point hint");
                    req.setPointHints(Arrays.asList(iter.getName(), iter2.getName()));
                }

                // put(algo + ".approximation", "BeelineSimplification").
                // put(algo + ".epsilon", 2);

                GHResponse rsp = new GHResponse();
                try {
                    hopper.calcPaths(req, rsp, querySettings.customModel());
                } catch (Exception ex) {
                    // 'not found' can happen if import creates more than one subnetwork
                    throw new RuntimeException("Error while calculating route! "
                            + "nodes:" + from + " -> " + to + ", request:" + req, ex);
                }

                if (rsp.hasErrors()) {
                    if (!warmup)
                        failedCount.incrementAndGet();

                    if (rsp.getErrors().get(0).getMessage() == null)
                        rsp.getErrors().get(0).printStackTrace();
                    else if (!toLowerCase(rsp.getErrors().get(0).getMessage()).contains("not found"))
                        logger.error("errors should NOT happen in Measurement! " + req + " => " + rsp.getErrors());

                    return 0;
                }

                PathWrapper arsp = rsp.getBest();
                if (!warmup) {
                    long visitedNodes = rsp.getHints().getLong("visited_nodes.sum", 0);
                    visitedNodesSum.addAndGet(visitedNodes);
                    if (visitedNodes > maxVisitedNodes.get()) {
                        maxVisitedNodes.set(visitedNodes);
                    }

                    long dist = (long) arsp.getDistance();
                    distSum.addAndGet(dist);

                    airDistSum.addAndGet((long) distCalc.calcDist(fromLat, fromLon, toLat, toLon));

                    if (dist > maxDistance.get())
                        maxDistance.set(dist);

                    if (dist < minDistance.get())
                        minDistance.set(dist);

                    if (querySettings.alternative)
                        altCount.addAndGet(rsp.getAll().size());
                }

                return arsp.getPoints().getSize();
            }
        }.setIterations(querySettings.count).start();

        int count = querySettings.count - failedCount.get();

        // if using non-bidirectional algorithm make sure you exclude CH routing
        String algoStr = (querySettings.ch && !querySettings.edgeBased) ? Algorithms.DIJKSTRA_BI : Algorithms.ASTAR_BI;
        if (querySettings.ch && !querySettings.sod) {
            algoStr += "_no_sod";
        }
        String prefix = querySettings.prefix;
        put(prefix + ".guessed_algorithm", algoStr);
        put(prefix + ".failed_count", failedCount.get());
        put(prefix + ".distance_min", minDistance.get());
        put(prefix + ".distance_mean", (float) distSum.get() / count);
        put(prefix + ".air_distance_mean", (float) airDistSum.get() / count);
        put(prefix + ".distance_max", maxDistance.get());
        put(prefix + ".visited_nodes_mean", (float) visitedNodesSum.get() / count);
        put(prefix + ".visited_nodes_max", (float) maxVisitedNodes.get());
        put(prefix + ".alternative_rate", (float) altCount.get() / count);
        print(prefix, miniPerf);
    }

    void print(String prefix, MiniPerfTest perf) {
        logger.info(prefix + ": " + perf.getReport());
        put(prefix + ".sum", perf.getSum());
        put(prefix + ".min", perf.getMin());
        put(prefix + ".mean", perf.getMean());
        put(prefix + ".max", perf.getMax());
    }

    void put(String key, Object val) {
        properties.put(key, val);
    }

    private void storeJson(String jsonLocation, boolean useMeasurementTimeAsRefTime) {
        logger.info("storing measurement json in " + jsonLocation);
        Map<String, String> gitInfoMap = new HashMap<>();
        // add git info if available
        if (Constants.GIT_INFO != null) {
            properties.remove("gh.gitinfo");
            gitInfoMap.put("commitHash", Constants.GIT_INFO.getCommitHash());
            gitInfoMap.put("commitMessage", Constants.GIT_INFO.getCommitMessage());
            gitInfoMap.put("commitTime", Constants.GIT_INFO.getCommitTime());
            gitInfoMap.put("branch", Constants.GIT_INFO.getBranch());
            gitInfoMap.put("dirty", String.valueOf(Constants.GIT_INFO.isDirty()));
        }
        Map<String, Object> result = new HashMap<>();
        // add measurement time, use same format as git commit time
        String measurementTime = new SimpleDateFormat("yyy-MM-dd'T'HH:mm:ssZ").format(new Date());
        result.put("measurementTime", measurementTime);
        // set ref time, this is either the git commit time or the measurement time
        if (Constants.GIT_INFO != null && !useMeasurementTimeAsRefTime) {
            result.put("refTime", Constants.GIT_INFO.getCommitTime());
        } else {
            result.put("refTime", measurementTime);
        }
        result.put("periodicBuild", useMeasurementTimeAsRefTime);
        result.put("gitinfo", gitInfoMap);
        result.put("metrics", properties);
        try {
            File file = new File(jsonLocation);
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(file, result);
        } catch (IOException e) {
            logger.error("Problem while storing json in: " + jsonLocation, e);
        }
    }

    private CustomModel createCustomModel() {
        CustomModel customModel = new CustomModel() {
            @Override
            public String toString() {
                return "truck";
            }
        };
        customModel.setBase("car");
        customModel.setVehicleHeight(3.8);
        customModel.setVehicleWidth(2.5);
        // the default distance_factor for custom requests is currently 1 which makes it too different regarding speed
        // compared to a normal car request. So, set it to 0 for a fair speed comparison.
        customModel.setDistanceFactorBase(0);

        Map<String, Object> map = new HashMap<>();
        map.put("motorway", 1.1);
        map.put("primary", 0.5);
        customModel.getPriority().put("road_class", map);
        map = new HashMap<>();
        map.put("no", 1.5);
        customModel.getPriority().put("toll", map);
        map = new HashMap<>();
        map.put("no", 0);
        customModel.getPriority().put("hazmat", map);

        map = new HashMap<>();
        map.put("motorway", 0.85);
        map.put("primary", 0.9);
        customModel.getSpeedFactor().put("road_class", map);

        customModel.setMaxSpeedFallback(110.0);

        return customModel;
    }

    private void storeProperties(String propLocation) {
        logger.info("storing measurement properties in " + propLocation);
        try (FileWriter fileWriter = new FileWriter(propLocation)) {
            String comment = "measurement finish, " + new Date().toString() + ", " + Constants.BUILD_DATE;
            fileWriter.append("#" + comment + "\n");
            for (Entry<String, Object> e : properties.entrySet()) {
                fileWriter.append(e.getKey());
                fileWriter.append("=");
                fileWriter.append(e.getValue().toString());
                fileWriter.append("\n");
            }
            fileWriter.flush();
        } catch (IOException e) {
            logger.error("Problem while storing properties in: " + propLocation, e);
        }
    }

    /**
     * Writes a selection of measurement results to a single line in
     * a file. Each run of the measurement class will append a new line.
     */
    private void writeSummary(String summaryLocation, String propLocation) {
        logger.info("writing summary to " + summaryLocation);
        // choose properties that should be in summary here
        String[] properties = {
                "graph.nodes",
                "graph.edges",
                "graph.import_time",
                CH.PREPARE + "time",
                CH.PREPARE + "node.time",
                CH.PREPARE + "edge.time",
                CH.PREPARE + "node.shortcuts",
                CH.PREPARE + "edge.shortcuts",
                Landmark.PREPARE + "time",
                "routing.distance_mean",
                "routing.mean",
                "routing.visited_nodes_mean",
                "routingCH.distance_mean",
                "routingCH.mean",
                "routingCH.visited_nodes_mean",
                "routingCH_no_instr.mean",
                "routingCH_full.mean",
                "routingCH_edge.distance_mean",
                "routingCH_edge.mean",
                "routingCH_edge.visited_nodes_mean",
                "routingCH_edge_no_instr.mean",
                "routingCH_edge_full.mean",
                "routingLM8.distance_mean",
                "routingLM8.mean",
                "routingLM8.visited_nodes_mean",
                "measurement.seed",
                "measurement.gitinfo",
                "measurement.timestamp"
        };
        File f = new File(summaryLocation);
        boolean writeHeader = !f.exists();
        try (FileWriter writer = new FileWriter(f, true)) {
            if (writeHeader)
                writer.write(getSummaryHeader(properties));
            writer.write(getSummaryLogLine(properties, propLocation));
        } catch (IOException e) {
            logger.error("Could not write summary to file '{}'", summaryLocation, e);
        }
    }

    private String getSummaryHeader(String[] properties) {
        StringBuilder sb = new StringBuilder("#");
        for (String p : properties) {
            String columnName = String.format("%" + getSummaryColumnWidth(p) + "s, ", p);
            sb.append(columnName);
        }
        sb.append("propertyFile");
        sb.append('\n');
        return sb.toString();
    }

    private String getSummaryLogLine(String[] properties, String propLocation) {
        StringBuilder sb = new StringBuilder(" ");
        for (String p : properties) {
            sb.append(getFormattedProperty(p));
        }
        sb.append(propLocation);
        sb.append('\n');
        return sb.toString();
    }

    private String getFormattedProperty(String property) {
        Object resultObj = properties.get(property);
        String result = resultObj == null ? "missing" : resultObj.toString();
        // limit number of decimal places for floating point numbers
        try {
            double doubleValue = Double.parseDouble(result.trim());
            if (doubleValue != (long) doubleValue) {
                result = String.format(Locale.US, "%.2f", doubleValue);
            }
        } catch (NumberFormatException e) {
            // its not a number, never mind
        }
        return String.format(Locale.US, "%" + getSummaryColumnWidth(property) + "s, ", result);
    }

    private int getSummaryColumnWidth(String p) {
        return Math.max(10, p.length());
    }
}
