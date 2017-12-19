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
package com.graphhopper.routing;

import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.*;

import java.util.PriorityQueue;

/**
 * Generic implementation of bidirectional Dijkstra algorithm that can be used with different shortest path entry types.
 *
 * @author Peter Karich
 */
public abstract class GenericDijkstraBidirection<T extends SPTEntry> extends AbstractBidirAlgo {
    protected IntObjectMap<T> bestWeightMapFrom;
    protected IntObjectMap<T> bestWeightMapTo;
    protected IntObjectMap<T> bestWeightMapOther;
    protected T currFrom;
    protected T currTo;
    protected PathBidirRef bestPath;
    PriorityQueue<T> pqOpenSetFrom;
    PriorityQueue<T> pqOpenSetTo;
    private boolean updateBestPath = true;

    public GenericDijkstraBidirection(Graph graph, Weighting weighting, TraversalMode tMode) {
        super(graph, weighting, tMode);
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 150_000);
        initCollections(size);
    }

    protected void initCollections(int size) {
        pqOpenSetFrom = new PriorityQueue<>(size);
        bestWeightMapFrom = new GHIntObjectHashMap<>(size);

        pqOpenSetTo = new PriorityQueue<>(size);
        bestWeightMapTo = new GHIntObjectHashMap<>(size);
    }

    @Override
    public void initFrom(int from, double weight) {
        currFrom = createStartEntry(from, weight);
        pqOpenSetFrom.add(currFrom);
        if (!traversalMode.isEdgeBased()) {
            bestWeightMapFrom.put(from, currFrom);
            if (currTo != null) {
                bestWeightMapOther = bestWeightMapTo;
                updateBestPath(GHUtility.getEdge(graph, from, currTo.adjNode), currTo, from);
            }
        } else if (currTo != null && currTo.adjNode == from) {
            // special case of identical start and end
            bestPath.sptEntry = currFrom;
            bestPath.edgeTo = currTo;
            finishedFrom = true;
            finishedTo = true;
        }
    }

    @Override
    public void initTo(int to, double weight) {
        currTo = createStartEntry(to, weight);
        pqOpenSetTo.add(currTo);
        if (!traversalMode.isEdgeBased()) {
            bestWeightMapTo.put(to, currTo);
            if (currFrom != null) {
                bestWeightMapOther = bestWeightMapFrom;
                updateBestPath(GHUtility.getEdge(graph, currFrom.adjNode, to), currFrom, to);
            }
        } else if (currFrom != null && currFrom.adjNode == to) {
            // special case of identical start and end
            bestPath.sptEntry = currFrom;
            bestPath.edgeTo = currTo;
            finishedFrom = true;
            finishedTo = true;
        }
    }

    @Override
    protected Path createAndInitPath() {
        bestPath = new PathBidirRef(graph, weighting);
        return bestPath;
    }

    @Override
    protected Path extractPath() {
        if (finished())
            return bestPath.extract();

        return bestPath;
    }

    @Override
    protected double getCurrentFromWeight() {
        return currFrom.weight;
    }

    @Override
    protected double getCurrentToWeight() {
        return currTo.weight;
    }

    @Override
    public boolean fillEdgesFrom() {
        if (pqOpenSetFrom.isEmpty())
            return false;

        currFrom = pqOpenSetFrom.poll();
        bestWeightMapOther = bestWeightMapTo;
        fillEdges(currFrom, pqOpenSetFrom, bestWeightMapFrom, outEdgeExplorer, false);
        visitedCountFrom++;
        return true;
    }

    @Override
    public boolean fillEdgesTo() {
        if (pqOpenSetTo.isEmpty())
            return false;
        currTo = pqOpenSetTo.poll();
        bestWeightMapOther = bestWeightMapFrom;
        fillEdges(currTo, pqOpenSetTo, bestWeightMapTo, inEdgeExplorer, true);
        visitedCountTo++;
        return true;
    }

    // http://www.cs.princeton.edu/courses/archive/spr06/cos423/Handouts/EPP%20shortest%20path%20algorithms.pdf
    // a node from overlap may not be on the best path!
    // => when scanning an arc (v, w) in the forward search and w is scanned in the reverseOrder
    //    search, update extractPath = μ if df (v) + (v, w) + dr (w) < μ
    @Override
    public boolean finished() {
        if (finishedFrom || finishedTo)
            return true;

        return currFrom.weight + currTo.weight >= bestPath.getWeight();
    }

    void fillEdges(T currEdge, PriorityQueue<T> prioQueue,
                   IntObjectMap<T> bestWeightMap, EdgeExplorer explorer, boolean reverse) {
        EdgeIterator iter = explorer.setBaseNode(currEdge.adjNode);
        while (iter.next()) {
            if (!accept(iter, currEdge.edge))
                continue;

            int traversalId = traversalMode.createTraversalId(iter, reverse);
            double tmpWeight = weighting.calcWeight(iter, reverse, currEdge.edge) + currEdge.weight;
            if (Double.isInfinite(tmpWeight))
                continue;
            T ee = bestWeightMap.get(traversalId);
            if (ee == null) {
                ee = createEntry(iter.getEdge(), iter.getAdjNode(), tmpWeight);
                ee.parent = currEdge;
                bestWeightMap.put(traversalId, ee);
                prioQueue.add(ee);
            } else if (ee.weight > tmpWeight) {
                prioQueue.remove(ee);
                ee.edge = iter.getEdge();
                ee.weight = tmpWeight;
                ee.parent = currEdge;
                prioQueue.add(ee);
            } else
                continue;

            if (updateBestPath)
                updateBestPath(iter, ee, traversalId);
        }
    }

    protected abstract T createStartEntry(int node, double weight);

    protected abstract T createEntry(int edge, int node, double weight);

    IntObjectMap<T> getBestFromMap() {
        return bestWeightMapFrom;
    }

    IntObjectMap<T> getBestToMap() {
        return bestWeightMapTo;
    }

    void setBestOtherMap(IntObjectMap<T> other) {
        bestWeightMapOther = other;
    }

    protected void setUpdateBestPath(boolean b) {
        updateBestPath = b;
    }

    void setBestPath(PathBidirRef bestPath) {
        this.bestPath = bestPath;
    }

}
