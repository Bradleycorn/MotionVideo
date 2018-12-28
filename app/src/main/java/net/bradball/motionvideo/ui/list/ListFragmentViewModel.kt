package net.bradball.motionvideo.ui.list

import androidx.lifecycle.ViewModel

class ListFragmentViewModel: ViewModel() {
    fun getItems(pageTitle: String): List<String> {
        return List(100) {
            "$pageTitle - ${it +1}"
        }
    }
}