package com.example.myfalldetectionapplitertpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.Switch
import androidx.fragment.app.Fragment

class ConfigurationFragment : Fragment() {

    private lateinit var spinnerModelFile: Spinner
    private lateinit var spinnerDeviceType: Spinner
    private lateinit var spinnerSensorType: Spinner
    private lateinit var switchTimeEmbedding: Switch

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_configuration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spinnerModelFile = view.findViewById(R.id.spinnerModelFile)
        spinnerDeviceType = view.findViewById(R.id.spinnerDeviceType)
        spinnerSensorType = view.findViewById(R.id.spinnerSensorType)
        switchTimeEmbedding = view.findViewById(R.id.switchTimeEmbedding)

        // TODO: Populate the spinners and set switch state from saved preferences.
    }
}
