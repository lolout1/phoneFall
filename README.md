# phoneFall


Current implemenation features fall detection model running on phone accelerometer and/or gyroscope data. Watch accelerometer/gyroscope data inference will be implemented in the near future where watch sensor values are sent to phone for processing so that GPU/computational pre-processing can be applied (e.g. sensor fusion, orientation estimation, interpolation, etc.) 

Time stamps can also optionally be concat with sensor data as input to the model but robust pre-processing feat. sensor fusion (magdwick, kalman, ekf) require aligned sensor data before filtering rendering this suboptimal and likely to cause an overfit model if using both accelerometer and gyroscope data during inference.

 
