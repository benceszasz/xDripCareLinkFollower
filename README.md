# xDrip+ CareLink Follower
> Experimental Medtronic CareLink Follower data source for xDrip+

Medtronic CareLink Follower data source for xDrip+ to retrieve data from Medtronic CareLink of online standalone CGM and insulin pump device data uploads (Guardian CGM and MiniMed 7xxG insulin pumps) inside and outside of US.

There is also a separate repository containing only the communication
with CareLink in Java:
[CareLinkJavaClient](https://github.com/benceszasz/CareLinkJavaClient)

## Supported devices
- Medtronic standalone CGMs (Guardian Connect (Enlite, Guardian 3) and Guardian (Guardian 4)) 
- Medtronic MiniMed 7XXG insulin pumps (740G, 770G, 780G)

## Features
Download from CareLink and display in xDrip:
- SG readings
- Insulin treatments: bolus and auto correction (only for pumps!)
- Meals (only for pumps!)
- Finger BGs
- Notifications
- Sensor status information (next calibration, remain lifetime)
- Pump status information (IOB, reservoir, battery) 
- Maximum Auto Basal (auto basal limit)

Upload to Nightscout using xDrip built-in Nightscout Sync feature:
- SG readings
- Insulin treatments (bolus and auto correction)
- Meals
- Finger BGs
- Notifications
 
## Limitations
- **CareLink MFA is not supported!!!**
- Notification texts are currently always in English
- Treatments of Guardian Connect and Guardian apps are not supported in xDrip, because
  these are just simple markers for CareLink followers, required details
  are missing (no insulin amount, no carb amount)

## Requirements
- CareLink Patient or Care Partner account **with MFA DISABLED**

## How to use it
- Download and install [latest release](https://github.com/benceszasz/xDripCareLinkFollower/releases)
- Configure CareLink Follower:
  - Select CareLink Follower for data source
  - Set CareLink country, username, password and patient username if using a care partner account with multiple patients
  - Select required data types: Finger BGs, Boluses, Meals,
    Notifications
  - If needed change grace period (wait after last sensor + 5 mins)
  - If needed change missed data polling interval (polling interval if
    data is not received after last sensor + 5 mins + grace period)
- Additional options:
  - Display pump status info (Settings > Less common settings > Extra
    status line > Pump Status)
  - Display sensor status info (Settings > Less common settings > Extra
    status line > External Status)
- If needed configure upload to Nightscout (Settings > Cloud
  Upload > Nightscout Sync), but **disable download data!**

## xDrip+ Logging
xDrip+ TAGs used for logging:
- CareLinkFollow : logs of CareLink Follower service
- CareLinkFollowDL : logs of CareLink data downloader
- CareLinkFollowDP : logs of CareLink data processor

## Credits
- CareLink data download core logic is based on the
  [Nightscout MiniMed Connect to Nightscout](https://github.com/nightscout/minimed-connect-to-nightscout)
- Follower data source logic is based on [xDrip+ Nightscout and DexShare
follower data sources](https://github.com/NightscoutFoundation/xDrip)

## Disclaimer And Warning
This project is intended for educational and informational purposes only. It relies on a series of fragile components and assumptions, any of which may break at any time. It is not FDA approved and should not be used to make medical decisions. It is neither affiliated with nor endorsed by Medtronic, and may violate their Terms of Service. Use of this code is without warranty or formal support of any kind.

## License

[agpl-3]: http://www.gnu.org/licenses/agpl-3.0.txt

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.
    
    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
