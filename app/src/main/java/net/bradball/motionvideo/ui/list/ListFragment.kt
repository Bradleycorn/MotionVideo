package net.bradball.motionvideo.ui.list

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import net.bradball.motionvideo.R

/**
 * A fragment representing a list of Items.
 */
class ListFragment: Fragment() {

    companion object {
        private const val ARG_TITLE = "arg_title"


        @JvmStatic
        fun newInstance(title: String) = ListFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
            }
        }
    }

    private val listTitle: String by lazy {
        arguments?.getString(ARG_TITLE) ?: ""
    }

    private lateinit var viewModel: ListFragmentViewModel


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(ListFragmentViewModel::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_list, container, false)

        // Set the adapter
        if (view is RecyclerView) {
            with(view) {
                layoutManager = LinearLayoutManager(context)
                adapter = ListItemAdapter(viewModel.getItems(listTitle))
            }
        }
        return view
    }

}
