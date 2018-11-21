from __future__ import absolute_import, division, print_function

from java import constructor, method, static_proxy, jint, jarray, jdouble, jboolean, jclass

from java.lang import String

from scipy.signal import butter, lfilter
from sklearn.decomposition import FastICA
import numpy as np

import scipy



class NpScipy(static_proxy()):
    @constructor([])
    def __init__(self):
        super(NpScipy, self).__init__()


    '''In PulseRateAlgorithm: 
                ica = FastICA(whiten=False)
                window = (window - np.mean(window, axis=0)) / \
                    np.std(window, axis=0)  # signal normalization
                # S = np.c_[array[cutlow:], array_1[cutlow:], array_2[cutlow:]]
                # S /= S.std(axis=0)
                # ica = FastICA(n_components=3)
                # print(np.isnan(window).any())
                # print(np.isinf(window).any())
                # ica = FastICA()
                window = np.reshape(window, (150, 1))
                S = ica.fit_transform(window)  # ICA Part
                ...
                ...
                detrend = scipy.signal.detrend(S)'''
    @method(jarray(jarray(jdouble)), [jarray(jdouble), jboolean])
    def get_detrend(self, window, dummyBoolean):
        ica = FastICA(whiten=False)
        window = np.asarray(window)
        window = (window - np.mean(window, axis=0)) / \
                 np.std(window, axis=0)  # signal normalization
        window = np.reshape(window, (150, 1))#NOTE: it was (150, 1)

        S = ica.fit_transform(window)  # ICA Part
        detrend = scipy.signal.detrend(S)
        return detrend.tolist()


    '''In PulseRateAlgorithm: 
                y = butter_bandpass_filter(
                    detrend, lowcut, highcut, fs, order=4)'''

    @method(jarray(jarray(jdouble)), [jarray(jarray(jdouble)), jdouble, jdouble, jdouble, jint])
    def butter_bandpass_filter(self, data, lowcut, highcut, fs, order):
        nyq = 0.5 * fs
        low = lowcut / nyq
        high = highcut / nyq
        b, a = butter(order, [low, high], btype='band')
        y = lfilter(b, a, data)
        return y


    '''In PulseRateAlgorithm: 
                powerSpec = np.abs(np.fft.fft(y, axis=0)) ** 2'''
    @method(jarray(jarray(jdouble)), [jarray(jarray(jdouble)), jboolean])
    def get_powerSpec(self, y, dummyBoolean):
        return (np.abs(np.fft.fft(y, axis=0)) ** 2).tolist()


    '''In PulseRateAlgorithm: 
                freqs = np.fft.fftfreq(150, 1.0 / 30)'''
    @method(jarray(jdouble), [jint, jdouble])
    def fftfreq(self,a, b):
        return np.fft.fftfreq(a, b).tolist()




