# TelescopeFocuser
Bachelor thesis

This thesis presents the design and implementation of a microcontroller-based control system
for the focuser of an amateur astronomical telescope. Proper focusing is one of the key factors
affecting the quality of visual observations and images acquired in astrophotography. In
traditional amateur solutions, focus adjustment is performed manually, which limits precision
and repeatability and makes remote operation difficult.
The developed system is based on an ATmega328P microcontroller, a stepper motor, and an
A4988 motor driver, enabling precise positioning of the telescope focuser. The motion control
is performed according to commands transmitted wirelessly from a mobile application.
Bluetooth Low Energy technology was used for communication, providing reliable data
transmission with low power consumption.
The scope of the thesis includes a discussion of the fundamental principles of image formation
in astronomical telescopes, limited to the aspects necessary to justify the required focusing
accuracy, an analysis of stepper motor operation, and the design of the power supply and
electronic circuitry. A control algorithm for the stepper motor and a communication protocol
between the mobile application and the control unit were also developed.
Experimental tests confirmed the correct operation of the proposed system and demonstrated
that it constitutes a solid basis for further development toward a fully automatic telescope
focuser employing image-based autofocus methods.
