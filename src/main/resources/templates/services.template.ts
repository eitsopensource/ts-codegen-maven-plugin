import { Inject, Injectable } from '@angular/core';
import { BROKER_CONFIGURATION, BrokerConfiguration, dwrWrapper, registerMethodTypes } from './services-wrapper';
import { Observable } from 'rxjs/Observable';
import { @IMPORTS@ } from './entities';

@SERVICES@