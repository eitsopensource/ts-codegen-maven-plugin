import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs/Observable';
import * as SockJS from 'sockjs-client';
import * as Stomp from '@stomp/stompjs';
import { Observer } from 'rxjs/Observer';

export interface MethodReturnTypes {
    [methodName: string]: string
}

export interface ServiceReturnTypes {
    [serviceName: string]: MethodReturnTypes
}

/**
 * path é o caminho SEM BARRA AO FINAL para o broker. Por padrão é simplesmente 'broker'
 */
export interface BrokerConfiguration {
    path: string,
    realTime: boolean,
    stompPath?: string
}

export let BROKER_CONFIGURATION = new InjectionToken<BrokerConfiguration>('broker.configuration');


/////////////////
/////////////////
/////////////////
/////////////////

type EntityEvent = 'PERSISTED' | 'UPDATED' | 'DELETED';

interface EngagedRealTimeMethods {
    [serviceAndMethodName: string]: EngagedMethodData
}

interface EngagedMethodData {
    args: any[],
    observable: Observable<any>,
    observer: Observer<any>
}


let stompClient: Stomp.Client;

const serviceReturnTypes: ServiceReturnTypes = {};
const engagedRealTimeMethods: EngagedRealTimeMethods = {};

export function registerMethodTypes(serviceName: string, returnTypes: MethodReturnTypes) {
    serviceReturnTypes[serviceName] = returnTypes;
}

export function dwrWrapper(configuration: BrokerConfiguration, serviceName: string, methodName: string, ...args: any[]): Observable<any> {
    if (configuration.realTime) {
        const combinedName = serviceName + '.' + methodName;
        if(!engagedRealTimeMethods[combinedName]) {
            const observable = Observable.create((observer: Observer<any>) => {
                engagedRealTimeMethods[combinedName] = {
                    args,
                    observer,
                    observable
                };
                lazyInitStompConnection(configuration).then(() => callEngagedMethod(configuration, serviceName, methodName, args, observer));
                return () => {
                    delete engagedRealTimeMethods[combinedName];
                }
            });
            return observable;
        }
        callEngagedMethod(configuration, serviceName, methodName, args, engagedRealTimeMethods[combinedName].observer);
        return engagedRealTimeMethods[combinedName].observable;
    } else {
        return Observable.create((observer: Observer<any>) => {
            loadDwrIfNeeded(configuration).then(() => {
                loadServiceIfNeeded(configuration, serviceName).then(service => {
                    args.push({
                        callback(result) {
                            observer.next(result);
                            observer.complete();
                        },
                        errorHandler(message, exception) {
                            observer.error({ message, exception });
                            console.error(message, exception);
                            observer.complete();
                        }
                    });
                    service[methodName].apply(service, args);
                });
            });
        });
    }
}

function loadDwrIfNeeded(configuration: BrokerConfiguration): Promise<void> {
    return new Promise<void>(resolve => {
        if ((window as any).dwr) {
            resolve();
        } else {
            const path = `${configuration.path}/engine.js`;
            const tag: HTMLScriptElement = document.createElement('script');
            tag.src = path;
            tag.type = 'text/javascript';
            tag.onload = () => resolve();
            tag.onerror = () => resolve();
            document.body.appendChild(tag);
        }
    });
}

function loadServiceIfNeeded(configuration: BrokerConfiguration, name: string): Promise<any> {
    return new Promise<any>(resolve => {
        if ((window as any)[name]) {
            resolve((window as any)[name]);
        } else {
            const path = `${configuration.path}/interface/${name}.js`;
            const tag: HTMLScriptElement = document.createElement('script');
            tag.src = path;
            tag.type = 'text/javascript';
            tag.onload = () => resolve((window as any)[name]);
            tag.onerror = () => resolve();
            document.body.appendChild(tag);
        }
    });
}

function lazyInitStompConnection(configuration: BrokerConfiguration): Promise<void> {
    return new Promise<void>(resolve => {
        if(stompClient && stompClient.connected) {
            resolve();
        } else {
            stompClient = Stomp.over(new SockJS(configuration.stompPath) as any);
            stompClient.connect({}, () => {
                stompClient.subscribe('/topic/persisted', message => handleStompMessage(configuration, 'PERSISTED', message));
                stompClient.subscribe('/topic/updated', message => handleStompMessage(configuration, 'UPDATED', message));
                stompClient.subscribe('/topic/deleted', message => handleStompMessage(configuration, 'DELETED', message));
                resolve();
            }, () => setTimeout(() => lazyInitStompConnection(configuration), 5000));
        }
    });
}

function handleStompMessage(configuration: BrokerConfiguration, event: EntityEvent, message: Stomp.Message) {
    const receivedEntity = JSON.parse(message.body, (key, value) => {
        const dateTimeRegex = /(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/;
        if (typeof value === 'string' && dateTimeRegex.exec(value)) {
            return new Date(value);
        }
        return value;
    });
    const fullType: string = receivedEntity ? receivedEntity['@type'] : null;

    Object.keys(serviceReturnTypes).forEach(service => {
        Object.keys(serviceReturnTypes[service]).forEach(method => {
            const data = engagedRealTimeMethods[service + '.' + method];
            if(data) {
                if (serviceReturnTypes[service][method] === fullType) {
                    callEngagedMethod(configuration, service, method, data.args, data.observer);
                } else if (serviceReturnTypes[service][method] === fullType + '[]') {
                    callEngagedMethod(configuration, service, method, data.args, data.observer);
                }
            }
        });
    });
}

function callEngagedMethod(configuration: BrokerConfiguration, serviceName: string, methodName: string, args: any[], observer: Observer<any>) {
    loadDwrIfNeeded(configuration).then(() => {
        loadServiceIfNeeded(configuration, serviceName).then(service => {
            const callback = {
                callback(result) {
                    observer.next(result);
                },
                errorHandler(message, exception) {
                    observer.error({ message, exception });
                    console.error(message, exception);
                }
            };
            if(args[args.length - 1].callback) {
                args[args.length - 1] = callback;
            } else {
                args.push(callback);
            }
            service[methodName].apply(service, args);
        });
    });
}