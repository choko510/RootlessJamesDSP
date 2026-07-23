package me.timschneeberger.rootlessjamesdsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDspBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null
    private var onInitialDraw: (() -> Unit)? = null
    private var onCardsReady: (() -> Unit)? = null
    private var initialDrawDispatched = false
    private var cardsReadyDispatched = false
    private var viewGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val generation = ++viewGeneration
        initialDrawDispatched = false
        cardsReadyDispatched = false

        binding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        binding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/rootlessjamesdsp")))
            hideTranslationNotice()
        }

        binding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        binding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        binding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        binding.updateNotice.isVisible = false

        val transition = LayoutTransition().apply {
            // Adding PreferenceFragments changes the height of the scroll container. Do not
            // animate the initial population; it creates extra layout passes during TTID.
            disableTransitionType(LayoutTransition.CHANGING)
        }
        binding.cardContainer.layoutTransition = transition

        val carAudioUiEnabled = BuildConfig.ROOTLESS && !BuildConfig.PLUGIN
        listOf(binding.cardAutoLoudness, binding.cardThreeBand, binding.cardCarSpatializer)
            .forEach { (it.parent as? ViewGroup)?.isVisible = carAudioUiEnabled }

        val cards = buildCardSpecs(carAudioUiEnabled)
        val initialCardCount = if (carAudioUiEnabled) INITIAL_CARD_COUNT_WITH_CAR else INITIAL_CARD_COUNT
        val initialCards = cards.take(initialCardCount)
        val remainingCards = cards.drop(initialCardCount)

        Trace.beginSection("DspFragment.initialCards")
        addCards(initialCards, commitImmediately = true)
        Trace.endSection()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }

        // Start the rest only after the first frame has had a chance to draw. Each subsequent
        // transaction is capped at three cards so a large preference tree cannot monopolize one
        // frame. LiveProg parsing consequently stays out of the TTID path.
        binding.root.doOnPreDraw {
            binding.root.postOnAnimation {
                if (generation != viewGeneration || this@DspFragment.view == null)
                    return@postOnAnimation

                if (!initialDrawDispatched) {
                    initialDrawDispatched = true
                    onInitialDraw?.invoke()
                }
                addRemainingCards(generation, remainingCards, 0, transition)
            }
        }
    }

    override fun onDestroyView() {
        viewGeneration++
        super.onDestroyView()
    }

    private fun buildCardSpecs(carAudioUiEnabled: Boolean): List<CardSpec> {
        return buildList {
            add(CardSpec(R.id.card_device_profiles) { DeviceProfilesCardFragment.newInstance() })
            if (carAudioUiEnabled) {
                add(CardSpec(R.id.card_auto_loudness) {
                    PreferenceGroupFragment.newInstance(Constants.PREF_AUTO_LOUDNESS, R.xml.dsp_auto_loudness_preferences)
                })
                add(CardSpec(R.id.card_three_band) {
                    PreferenceGroupFragment.newInstance(Constants.PREF_THREE_BAND_COMPRESSOR, R.xml.dsp_three_band_preferences)
                })
                add(CardSpec(R.id.card_car_spatializer) {
                    PreferenceGroupFragment.newInstance(Constants.PREF_CAR_SPATIALIZER, R.xml.dsp_car_spatializer_preferences)
                })
            }
            add(CardSpec(R.id.card_output_control) {
                PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT, R.xml.dsp_output_control_preferences)
            })
            add(CardSpec(R.id.card_compressor) {
                PreferenceGroupFragment.newInstance(Constants.PREF_COMPANDER, R.xml.dsp_compander_preferences)
            })
            add(CardSpec(R.id.card_bass) {
                PreferenceGroupFragment.newInstance(Constants.PREF_BASS, R.xml.dsp_bass_preferences)
            })
            add(CardSpec(R.id.card_eq) {
                PreferenceGroupFragment.newInstance(Constants.PREF_EQ, R.xml.dsp_equalizer_preferences)
            })
            add(CardSpec(R.id.card_peq) {
                PreferenceGroupFragment.newInstance(Constants.PREF_PEQ, R.xml.dsp_parametriceq_preferences)
            })
            add(CardSpec(R.id.card_geq) {
                PreferenceGroupFragment.newInstance(Constants.PREF_GEQ, R.xml.dsp_graphiceq_preferences)
            })
            add(CardSpec(R.id.card_ddc) {
                PreferenceGroupFragment.newInstance(Constants.PREF_DDC, R.xml.dsp_ddc_preferences)
            })
            add(CardSpec(R.id.card_convolver) {
                PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER, R.xml.dsp_convolver_preferences)
            })
            add(CardSpec(R.id.card_liveprog) {
                PreferenceGroupFragment.newInstance(Constants.PREF_LIVEPROG, R.xml.dsp_liveprog_preferences)
            })
            add(CardSpec(R.id.card_tube) {
                PreferenceGroupFragment.newInstance(Constants.PREF_TUBE, R.xml.dsp_tube_preferences)
            })
            add(CardSpec(R.id.card_stereowide) {
                PreferenceGroupFragment.newInstance(Constants.PREF_STEREOWIDE, R.xml.dsp_stereowide_preferences)
            })
            add(CardSpec(R.id.card_crossfeed) {
                PreferenceGroupFragment.newInstance(Constants.PREF_CROSSFEED, R.xml.dsp_crossfeed_preferences)
            })
            add(CardSpec(R.id.card_reverb) {
                PreferenceGroupFragment.newInstance(Constants.PREF_REVERB, R.xml.dsp_reverb_preferences)
            })
        }
    }

    private fun addCards(cards: List<CardSpec>, commitImmediately: Boolean): Boolean {
        val transaction = childFragmentManager.beginTransaction()
        var changed = false
        cards.forEach { spec ->
            val existing = childFragmentManager.findFragmentByTag(spec.tag)
                ?: childFragmentManager.findFragmentById(spec.containerId)
            if (existing == null) {
                transaction.add(spec.containerId, spec.factory(), spec.tag)
                changed = true
            }
        }

        if (!changed)
            return false

        if (commitImmediately)
            transaction.commitNow()
        else
            transaction.commit()
        return true
    }

    private fun addRemainingCards(
        generation: Int,
        cards: List<CardSpec>,
        startIndex: Int,
        transition: LayoutTransition,
    ) {
        if (generation != viewGeneration || view == null)
            return

        val batch = cards.drop(startIndex).take(MAX_CARDS_PER_FRAME)
        if (batch.isEmpty()) {
            binding.root.doOnPreDraw {
                if (generation != viewGeneration || cardsReadyDispatched)
                    return@doOnPreDraw

                transition.enableTransitionType(LayoutTransition.CHANGING)
                // doOnPreDraw runs before the traversal is submitted. Post the callback one frame
                // later so reportFullyDrawn is emitted only after the final card has actually
                // participated in a draw.
                binding.root.postOnAnimation {
                    if (generation != viewGeneration || cardsReadyDispatched)
                        return@postOnAnimation
                    cardsReadyDispatched = true
                    onCardsReady?.invoke()
                }
            }
            return
        }

        Trace.beginSection("DspFragment.cards.${startIndex}-${startIndex + batch.size}")
        addCards(batch, commitImmediately = false)
        Trace.endSection()
        binding.root.postOnAnimation {
            addRemainingCards(generation, cards, startIndex + batch.size, transition)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!::binding.isInitialized || view == null)
            return

        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                (binding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun hideTranslationNotice() {
        binding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    fun setUpdateCardVisible(visible: Boolean) {
        binding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        binding.updateNotice.titleText = title
    }

    fun setUpdateCardOnClick(onClick: () -> Unit) {
        updateNoticeOnClick = onClick
    }

    fun setUpdateCardOnCloseClick(onClick: () -> Unit) {
        updateNoticeOnCloseClick = onClick
    }

    fun setOnInitialDrawListener(listener: () -> Unit) {
        onInitialDraw = listener
        if (initialDrawDispatched)
            listener()
    }

    fun setOnCardsReadyListener(listener: () -> Unit) {
        onCardsReady = listener
        if (cardsReadyDispatched)
            listener()
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        try {
            childFragmentManager.beginTransaction()
                .replace(id, newFragment)
                .commitAllowingStateLoss()
        }
        catch(ex: IllegalStateException) {
            Timber.e("Failed to restart fragment")
            Timber.i(ex)
        }
    }

    private data class CardSpec(
        val containerId: Int,
        val factory: () -> Fragment,
    ) {
        val tag = "dsp-card:$containerId"
    }

    companion object {
        private const val INITIAL_CARD_COUNT = 2
        private const val INITIAL_CARD_COUNT_WITH_CAR = 5
        private const val MAX_CARDS_PER_FRAME = 3

        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
