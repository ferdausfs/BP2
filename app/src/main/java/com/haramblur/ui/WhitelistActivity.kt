package com.haramblur.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.haramblur.R
import com.haramblur.databinding.ActivityWhitelistBinding
import com.haramblur.utils.Settings

class WhitelistActivity : AppCompatActivity() {

    private lateinit var b: ActivityWhitelistBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = "Whitelist Apps"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        settings = Settings(this)

        val pm   = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        b.recyclerWhitelist.layoutManager = LinearLayoutManager(this)
        b.recyclerWhitelist.adapter       = AppAdapter(apps, pm)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    inner class AppAdapter(
        private val apps: List<ApplicationInfo>,
        private val pm:   PackageManager
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivAppIcon)
            val name: TextView  = view.findViewById(R.id.tvAppName)
            val pkg:  TextView  = view.findViewById(R.id.tvPackageName)
            val cb:   CheckBox  = view.findViewById(R.id.cbWhitelist)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val app = apps[pos]
            h.icon.setImageDrawable(pm.getApplicationIcon(app))
            h.name.text = pm.getApplicationLabel(app)
            h.pkg.text  = app.packageName
            h.cb.setOnCheckedChangeListener(null)
            h.cb.isChecked = settings.isWhitelisted(app.packageName)
            h.cb.setOnCheckedChangeListener { _, on ->
                if (on) settings.addToWhitelist(app.packageName)
                else    settings.removeFromWhitelist(app.packageName)
            }
            h.itemView.setOnClickListener { h.cb.isChecked = !h.cb.isChecked }
        }
    }
}
