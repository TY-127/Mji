package com.moon.aiphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

class MessageListFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var headerBar: RelativeLayout
    private lateinit var tvHeaderTitle: TextView

    private val msgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshList() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_message_list, container, false)
        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        headerBar = view.findViewById(R.id.msgListHeader)
        tvHeaderTitle = view.findViewById(R.id.tvMsgListTitle)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        applyTheme()
        return view
    }

    private fun applyTheme() {
        if (!::headerBar.isInitialized) return
        ThemeManager.applyHeader(headerBar, tvHeaderTitle)
        val listBg = ThemeManager.getColor("--msg-list-bg", android.graphics.Color.TRANSPARENT)
        if (::recyclerView.isInitialized) recyclerView.setBackgroundColor(listBg)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(), msgReceiver,
            IntentFilter("CYBER_NEW_MSG"), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        applyTheme()
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(msgReceiver) } catch (_: Exception) {}
    }

    private fun refreshList() {
        val dbHelper = DatabaseHelper(requireContext())
        val singles = dbHelper.getRecentChats()
        val rawGroups = dbHelper.getRecentGroups()
        val merged = (singles + rawGroups).sortedWith(
            compareByDescending<RecentChat> { it.isPinned }
                .thenByDescending { parseMsgTimeToLong(it.msgTime) }
        )
        recyclerView.adapter = RecentChatAdapter(merged)
        tvEmpty.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (merged.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun parseMsgTimeToLong(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).parse(s)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}