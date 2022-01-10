package com.steelph0enix.weatherstationapp

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener

class LineChartManager(
    private val chart: LineChart,
    private val appContext: Context,
) :
    OnChartValueSelectedListener {


    fun initializeChart(name: String, color: Int, minY: Float, maxY: Float) {
        chart.setOnChartValueSelectedListener(this)
        chart.setDrawGridBackground(true)
        chart.setBackgroundColor(Color.argb(100, 255, 255, 255))
        chart.setGridBackgroundColor(Color.argb(200, 255, 255, 255))
        chart.description.isEnabled = false
        chart.setNoDataText("No data available (yet), connect to station!")
        chart.axisLeft.axisMinimum = minY
        chart.axisLeft.axisMaximum = maxY

        if (chart.data == null) {
            chart.data = LineData()
        }

        var dataSet = chart.data.getDataSetByIndex(0)

        if (dataSet == null) {
            dataSet = createDataSet(name, color)
            chart.data.addDataSet(dataSet)
        }

        chart.invalidate()
    }

    fun addValue(x: Float, y: Float) {
        chart.data.addEntry(Entry(x, y), 0)
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    override fun onValueSelected(e: Entry?, h: Highlight?) {
        Toast.makeText(appContext, e.toString(), Toast.LENGTH_SHORT).show()
    }

    override fun onNothingSelected() {

    }

    private fun createDataSet(name: String, color: Int): LineDataSet {
        val set = LineDataSet(null, name)
        set.color = color
        set.axisDependency = YAxis.AxisDependency.LEFT
        return set
    }
}