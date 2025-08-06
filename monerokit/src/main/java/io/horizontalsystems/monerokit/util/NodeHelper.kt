package io.horizontalsystems.monerokit.util

import io.horizontalsystems.monerokit.data.DefaultNodes
import io.horizontalsystems.monerokit.data.NodeInfo
import java.util.Collections

object NodeHelper {

    private var favouriteNodes: MutableSet<NodeInfo> = HashSet<NodeInfo>()

    fun getOrPopulateFavourites(): MutableSet<NodeInfo> {
        if (favouriteNodes.isEmpty()) {
            for (node in DefaultNodes.entries) {
                val nodeInfo = NodeInfo.fromString(node.uri)
                if (nodeInfo != null) {
                    nodeInfo.favourite = true
//                    nodeInfo.setFavourite(true)
                    favouriteNodes.add(nodeInfo)
                }
            }
        }
        return favouriteNodes
    }

    fun autoselect(nodes: MutableSet<NodeInfo>): NodeInfo? {
        if (nodes.isEmpty()) return null
        NodePinger.execute(nodes, null)
        val nodeList: MutableList<NodeInfo?> = ArrayList(nodes)
        Collections.sort<NodeInfo?>(nodeList, NodeInfo.BestNodeComparator)
        return nodeList[0]
    }
}